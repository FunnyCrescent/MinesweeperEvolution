/**
 * engine.d — Игровое ядро Сапёр: Эволюция на D
 * 
 * @nogc совместимый. Память через Arena Allocator (C malloc).
 * Компилируется через LDC2 для aarch64-linux-android.
 */
module engine;

import core.stdc.stdlib : malloc;
import core.stdc.string : memcpy, memset;

extern(C):

// ===== Типы =====

alias bool_t = ubyte;

enum GameMode : ubyte {
    CLASSIC   = 0,
    DRIFT     = 1,
    CHAOS     = 2,
    ANARCHY   = 3,
    AVALANCHE = 4,
}

enum RevealResult : ubyte {
    NO_CHANGE = 0,
    REVEALED  = 1,
    EXPLODED  = 2,
    WON       = 3,
}

// ===== Структура движка =====

struct GameEngine {
    // Размеры поля
    int rows;
    int cols;
    
    // Режим
    GameMode mode;
    ubyte hasMineLimit;
    ubyte shifts;
    ubyte preservesFlags;
    ubyte coversAllAfterShift;
    
    // Состояние
    int mineCount;
    int flaggedCount;
    int revealedCount;
    int shiftsCount;
    bool_t gameOver;
    bool_t won;
    bool_t firstClickDone;
    
    // Указатели на массивы (выделены в арене)
    bool_t* mines;     // [rows * cols]
    bool_t* revealed;   // [rows * cols]
    bool_t* flagged;    // [rows * cols]
    
    // Последняя открытая клетка (для взрыва)
    int explodedRow;
    int explodedCol;
    
    // Сид для ГПСЧ
    uint rngState;
}

// ===== Arena Allocator =====

struct Arena {
    void* buffer;
    size_t capacity;
    size_t offset;
}

Arena* g_arena;

void arena_init(size_t capacity) {
    g_arena = cast(Arena*) malloc(Arena.sizeof);
    g_arena.buffer = malloc(capacity);
    g_arena.capacity = capacity;
    g_arena.offset = 0;
}

void* arena_alloc(size_t size) @nogc {
    if (g_arena.offset + size > g_arena.capacity) return null;
    void* ptr = cast(void*)(cast(size_t)g_arena.buffer + g_arena.offset);
    g_arena.offset += size;
    return ptr;
}

void arena_reset() @nogc {
    if (g_arena) g_arena.offset = 0;
}

// ===== Простой ГПСЧ (xorshift) =====

uint rng_next(ref uint state) @nogc {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return state;
}

int rng_range(ref uint state, int max) @nogc {
    if (max <= 0) return 0;
    return cast(int)(rng_next(state) % cast(uint)max);
}

// ===== Индексация =====

int idx(GameEngine* e, int r, int c) @nogc {
    return r * e.cols + c;
}

bool_t getCell(bool_t* arr, int rows, int cols, int r, int c) @nogc {
    if (r < 0 || r >= rows || c < 0 || c >= cols) return 0;
    return arr[r * cols + c];
}

void setCell(bool_t* arr, int cols, int r, int c, bool_t val) @nogc {
    arr[r * cols + c] = val;
}

// ===== Подсчёт соседних мин =====

int adjacentMines(GameEngine* e, int row, int col) @nogc {
    int count = 0;
    for (int dr = -1; dr <= 1; dr++) {
        for (int dc = -1; dc <= 1; dc++) {
            if (dr == 0 && dc == 0) continue;
            int nr = row + dr;
            int nc = col + dc;
            if (nr >= 0 && nr < e.rows && nc >= 0 && nc < e.cols) {
                if (e.mines[nr * e.cols + nc]) count++;
            }
        }
    }
    return count;
}

// ===== Инициализация игры =====

void engine_init(GameEngine* e, GameMode mode, int rows, int cols, int mineCount) @nogc {
    e.rows = rows;
    e.cols = cols;
    e.mode = mode;
    
    // Параметры режима
    final switch (mode) {
        case GameMode.CLASSIC:
            e.hasMineLimit = 1; e.shifts = 0; e.preservesFlags = 1; e.coversAllAfterShift = 0;
            break;
        case GameMode.DRIFT:
            e.hasMineLimit = 1; e.shifts = 1; e.preservesFlags = 1; e.coversAllAfterShift = 0;
            break;
        case GameMode.CHAOS:
            e.hasMineLimit = 1; e.shifts = 1; e.preservesFlags = 0; e.coversAllAfterShift = 0;
            break;
        case GameMode.ANARCHY:
            e.hasMineLimit = 0; e.shifts = 1; e.preservesFlags = 0; e.coversAllAfterShift = 0;
            break;
        case GameMode.AVALANCHE:
            e.hasMineLimit = 1; e.shifts = 1; e.preservesFlags = 1; e.coversAllAfterShift = 1;
            break;
    }
    
    e.mineCount = e.hasMineLimit ? mineCount : (1 + rng_range(e.rngState, rows * cols));
    e.flaggedCount = 0;
    e.revealedCount = 0;
    e.shiftsCount = 0;
    e.gameOver = 0;
    e.won = 0;
    e.firstClickDone = 0;
    e.explodedRow = -1;
    e.explodedCol = -1;
    e.rngState = cast(uint)mineCount * 2654435761u; // простой сид
    
    // Выделяем массивы в арене
    size_t total = cast(size_t)(rows * cols);
    e.mines = cast(bool_t*) arena_alloc(total);
    e.revealed = cast(bool_t*) arena_alloc(total);
    e.flagged = cast(bool_t*) arena_alloc(total);
    
    memset(e.mines, 0, total);
    memset(e.revealed, 0, total);
    memset(e.flagged, 0, total);
    
    // Расставляем мины случайно
    placeMinesRandomly(e, null, 0);
    
    if (e.mineCount == 0) {
        forcePlaceOneMine(e);
    }
}

void placeMinesRandomly(GameEngine* e, int* exclude, int excludeCount) @nogc {
    int target = e.mineCount;
    
    // Очищаем
    memset(e.mines, 0, cast(size_t)(e.rows * e.cols));
    
    // Простой случайный выбор
    int placed = 0;
    int attempts = 0;
    while (placed < target && attempts < target * 10) {
        int r = rng_range(e.rngState, e.rows);
        int c = rng_range(e.rngState, e.cols);
        int i = r * e.cols + c;
        
        if (e.mines[i]) { attempts++; continue; }
        if (e.revealed[i]) { attempts++; continue; }
        
        bool_t excluded = 0;
        for (int j = 0; j < excludeCount; j++) {
            if (exclude[j] == i) { excluded = 1; break; }
        }
        if (excluded) { attempts++; continue; }
        
        e.mines[i] = 1;
        placed++;
    }
    e.mineCount = placed;
}

void forcePlaceOneMine(GameEngine* e) @nogc {
    for (int r = 0; r < e.rows; r++) {
        for (int c = 0; c < e.cols; c++) {
            if (!e.revealed[r * e.cols + c] && !e.mines[r * e.cols + c]) {
                e.mines[r * e.cols + c] = 1;
                e.mineCount = 1;
                return;
            }
        }
    }
}

// ===== Безопасный первый клик =====

void ensureSafeStart(GameEngine* e, int row, int col) @nogc {
    // Собираем safe зону (клик + 8 соседей)
    // Мины под флажками НЕ трогаем
    for (int dr = -1; dr <= 1; dr++) {
        for (int dc = -1; dc <= 1; dc++) {
            int nr = row + dr;
            int nc = col + dc;
            if (nr >= 0 && nr < e.rows && nc >= 0 && nc < e.cols) {
                if (!e.flagged[nr * e.cols + nc]) {
                    e.mines[nr * e.cols + nc] = 0;
                }
            }
        }
    }
    
    // Дополняем мины в случайные места
    int needed = e.mineCount;
    int actual = 0;
    for (int i = 0; i < e.rows * e.cols; i++) {
        if (e.mines[i]) actual++;
    }
    
    while (actual < needed) {
        int r = rng_range(e.rngState, e.rows);
        int c = rng_range(e.rngState, e.cols);
        int i = r * e.cols + c;
        if (!e.mines[i] && !e.revealed[i] && !e.flagged[i]) {
            // Не в safe зоне
            bool_t inSafe = 0;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    if (r == row + dr && c == col + dc) { inSafe = 1; break; }
                }
            }
            if (!inSafe) {
                e.mines[i] = 1;
                actual++;
            }
        }
    }
}

// ===== Открытие клетки =====

RevealResult engine_reveal(GameEngine* e, int row, int col) @nogc {
    if (e.gameOver || e.won) return RevealResult.NO_CHANGE;
    if (row < 0 || row >= e.rows || col < 0 || col >= e.cols) return RevealResult.NO_CHANGE;
    int i = row * e.cols + col;
    if (e.revealed[i] || e.flagged[i]) return RevealResult.NO_CHANGE;
    
    if (!e.firstClickDone) {
        e.firstClickDone = 1;
        ensureSafeStart(e, row, col);
    }
    
    e.revealed[i] = 1;
    e.revealedCount++;
    
    if (e.mines[i]) {
        e.explodedRow = row;
        e.explodedCol = col;
        e.gameOver = 1;
        return RevealResult.EXPLODED;
    }
    
    if (adjacentMines(e, row, col) == 0) {
        floodReveal(e, row, col);
    }
    
    if (checkWin(e)) {
        e.won = 1;
        e.gameOver = 1;
        return RevealResult.WON;
    }
    
    return RevealResult.REVEALED;
}

void floodReveal(GameEngine* e, int row, int col) @nogc {
    // Стек для flood fill (без рекурсии — @nogc совместимо)
    int stackSize = e.rows * e.cols;
    int* stack = cast(int*) arena_alloc(cast(size_t)stackSize * int.sizeof);
    if (!stack) return;
    
    int sp = 0;
    stack[sp++] = row * e.cols + col;
    
    while (sp > 0) {
        int idx = stack[--sp];
        int r = idx / e.cols;
        int c = idx % e.cols;
        
        if (e.mines[idx]) continue;
        if (!e.revealed[idx]) {
            e.revealed[idx] = 1;
            e.revealedCount++;
        }
        
        if (adjacentMines(e, r, c) == 0) {
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) continue;
                    int nr = r + dr;
                    int nc = c + dc;
                    if (nr >= 0 && nr < e.rows && nc >= 0 && nc < e.cols) {
                        int ni = nr * e.cols + nc;
                        if (!e.revealed[ni] && !e.flagged[ni] && !e.mines[ni]) {
                            stack[sp++] = ni;
                        }
                    }
                }
            }
        }
    }
}

// ===== Флаг =====

bool_t engine_toggleFlag(GameEngine* e, int row, int col) @nogc {
    if (e.gameOver || e.won) return 0;
    if (row < 0 || row >= e.rows || col < 0 || col >= e.cols) return 0;
    int i = row * e.cols + col;
    if (e.revealed[i]) return 0;
    
    e.flagged[i] = !e.flagged[i];
    e.flaggedCount += e.flagged[i] ? 1 : -1;
    
    // Проверка победы в Лавине
    if (e.flagged[i] && e.coversAllAfterShift && checkWin(e)) {
        e.won = 1;
        e.gameOver = 1;
    }
    
    return 1;
}

// ===== Проверка победы =====

bool_t checkWin(GameEngine* e) @nogc {
    if (e.coversAllAfterShift) {
        // Лавина: все мины помечены И нет неверных флагов
        for (int i = 0; i < e.rows * e.cols; i++) {
            if (e.mines[i] && !e.flagged[i]) return 0;
            if (!e.mines[i] && e.flagged[i]) return 0;
        }
        return 1;
    }
    // Обычная: все не-минные клетки открыты
    for (int i = 0; i < e.rows * e.cols; i++) {
        if (!e.mines[i] && !e.revealed[i]) return 0;
    }
    return 1;
}

// ===== Сдвиг мин =====

void engine_shiftMines(GameEngine* e) @nogc {
    if (e.gameOver || !e.shifts) return;
    
    // Список замороженных мин (под флажком)
    int frozenCount = 0;
    for (int i = 0; i < e.rows * e.cols; i++) {
        if (e.flagged[i] && e.mines[i]) frozenCount++;
    }
    
    if (!e.preservesFlags) {
        // CHAOS / ANARCHY: сброс флагов
        memset(e.flagged, 0, cast(size_t)(e.rows * e.cols));
        e.flaggedCount = 0;
        frozenCount = 0;
    }
    
    // Новое число мин (для Анархии)
    int newCount = e.hasMineLimit ? e.mineCount : (1 + rng_range(e.rngState, e.rows * e.cols));
    
    // Сброс мин, кроме замороженных
    for (int i = 0; i < e.rows * e.cols; i++) {
        if (e.flagged[i] && e.mines[i]) {
            // замороженная — оставляем
        } else {
            e.mines[i] = 0;
        }
    }
    
    // Расставляем остальные мины
    int remaining = newCount - frozenCount;
    if (remaining > 0) {
        for (int attempt = 0; attempt < remaining * 10; attempt++) {
            int r = rng_range(e.rngState, e.rows);
            int c = rng_range(e.rngState, e.cols);
            int i = r * e.cols + c;
            if (!e.mines[i] && !e.revealed[i] && !e.flagged[i]) {
                e.mines[i] = 1;
                remaining--;
                if (remaining <= 0) break;
            }
        }
    }
    
    e.mineCount = frozenCount + (newCount - frozenCount - remaining);
    e.shiftsCount++;
    
    // Лавина: проверка неверных флагов → поражение
    if (e.coversAllAfterShift) {
        for (int i = 0; i < e.rows * e.cols; i++) {
            if (e.flagged[i] && !e.mines[i]) {
                e.explodedRow = i / e.cols;
                e.explodedCol = i % e.cols;
                e.gameOver = 1;
                return;
            }
        }
        // Закрываем поле
        memset(e.revealed, 0, cast(size_t)(e.rows * e.cols));
        e.firstClickDone = 0;
        e.revealedCount = 0;
    }
    
    if (e.mineCount == 0) forcePlaceOneMine(e);
}

// ===== Доступ =====

bool_t engine_isMine(GameEngine* e, int row, int col) @nogc {
    return e.mines[row * e.cols + col];
}

bool_t engine_isRevealed(GameEngine* e, int row, int col) @nogc {
    return e.revealed[row * e.cols + col];
}

bool_t engine_isFlagged(GameEngine* e, int row, int col) @nogc {
    return e.flagged[row * e.cols + col];
}

int engine_adjacentMines(GameEngine* e, int row, int col) @nogc {
    return adjacentMines(e, row, col);
}

int engine_minesLeft(GameEngine* e) @nogc {
    return e.mineCount - e.flaggedCount;
}
