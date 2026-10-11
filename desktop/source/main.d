/**
 * main.d — Desktop-порт «Сапёр: Эволюция» на D + SDL2.
 *
 * Сборка:
 *   ldc2 main.d -I/home/z/my-project/sdl-extract/usr/include \
 *     -L/home/z/my-project/sdl-extract/usr/lib/x86_64-linux-gnu \
 *     -L-lSDL2 -L-lSDL2_image -L-lSDL2_ttf -L-lm \
 *     -of=minesweeper
 *
 * Запуск:
 *   LD_LIBRARY_PATH=/home/z/my-project/sdl-rt/usr/lib/x86_64-linux-gnu ./minesweeper
 */
module main;

import core.stdc.stdlib : exit;
import std.stdio;

// Helper: abs for int
int absInt(int x) { return x < 0 ? -x : x; }

// SDL2 bindings (minimal, via extern(C))
extern(C):
struct SDL_Window;
struct SDL_Renderer;
struct SDL_Surface;
struct SDL_Texture;
struct SDL_Event;
struct SDL_Rect { int x, y, w, h; }

// SDL2 functions
SDL_Window* SDL_CreateWindow(const(char)* title, int x, int y, int w, int h, uint flags);
void SDL_DestroyWindow(SDL_Window* window);
int SDL_Init(uint flags);
void SDL_Quit();
int SDL_PollEvent(SDL_Event* event);
void SDL_Delay(uint ms);
uint SDL_GetTicks();
SDL_Renderer* SDL_CreateRenderer(SDL_Window* window, int index, uint flags);
void SDL_DestroyRenderer(SDL_Renderer* renderer);
int SDL_SetRenderDrawColor(SDL_Renderer* renderer, ubyte r, ubyte g, ubyte b, ubyte a);
int SDL_RenderClear(SDL_Renderer* renderer);
void SDL_RenderPresent(SDL_Renderer* renderer);
int SDL_RenderFillRect(SDL_Renderer* renderer, const(SDL_Rect)* rect);
int SDL_RenderCopy(SDL_Renderer* renderer, SDL_Texture* texture, const(SDL_Rect)* srcrect, const(SDL_Rect)* dstrect);
int SDL_RenderDrawRect(SDL_Renderer* renderer, const(SDL_Rect)* rect);
SDL_Texture* SDL_CreateTextureFromSurface(SDL_Renderer* renderer, SDL_Surface* surface);
void SDL_DestroyTexture(SDL_Texture* texture);
int SDL_QueryTexture(SDL_Texture* texture, uint* format, int* access, int* w, int* h);
int SDL_SetTextureAlphaMod(SDL_Texture* texture, ubyte alpha);

// SDL2_image
SDL_Surface* IMG_Load(const(char)* file);
void SDL_FreeSurface(SDL_Surface* surface);

// SDL2_ttf
int TTF_Init();
void TTF_Quit();
struct TTF_Font;
TTF_Font* TTF_OpenFont(const(char)* file, int ptsize);
void TTF_CloseFont(TTF_Font* font);
SDL_Surface* TTF_RenderUTF8_Blended(TTF_Font* font, const(char)* text, SDL_Color color);
struct SDL_Color { ubyte r, g, b, a; }

// Constants
enum SDL_INIT_VIDEO = 0x20;
enum SDL_WINDOWPOS_UNDEFINED = 0x1FFF0000;
enum SDL_RENDERER_ACCELERATED = 0x02;
enum SDL_RENDERER_PRESENTVSYNC = 0x04;
enum SDL_QUIT = 0x100;
enum SDL_MOUSEBUTTONDOWN = 0x401;
enum SDL_MOUSEBUTTONUP = 0x402;
enum SDL_BUTTON_LEFT = 1;
enum SDL_BUTTON_RIGHT = 3;

struct SDL_CommonEvent { uint type; uint timestamp; }
struct SDL_WindowEvent { uint type; uint timestamp; uint windowID; ubyte event; int padding1; int data1; int data2; }
struct SDL_MouseButtonEvent { uint type; uint timestamp; uint windowID; uint which; ubyte button; ubyte state; ubyte clicks; int padding1; int x; int y; }

// ============ Game Logic ============

enum GameMode { CLASSIC, DRIFT, CHAOS, ANARCHY, AVALANCHE }
enum RevealResult { NO_CHANGE, REVEALED, EXPLODED, WON }

struct Cell {
    bool mine;
    bool revealed;
    bool flagged;
}

class Engine {
    int rows, cols;
    GameMode mode;
    Cell[][] grid;
    int mineCount, flaggedCount, revealedCount;
    bool gameOver, won, firstClickDone;
    int explodedRow = -1, explodedCol = -1;

    this(int rows, int cols, int mineCount, GameMode mode = GameMode.CLASSIC) {
        this.rows = rows;
        this.cols = cols;
        this.mode = mode;
        this.mineCount = mineCount;
        grid = new Cell[][](rows);
        foreach (ref row; grid) row = new Cell[](cols);
    }

    int adjacentMines(int r, int c) {
        int count = 0;
        for (int dr = -1; dr <= 1; dr++)
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int nr = r + dr, nc = c + dc;
                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && grid[nr][nc].mine) count++;
            }
        return count;
    }

    void placeMines(int excludeR, int excludeC) {
        import std.random;
        auto rng = Random(unpredictableSeed);
        int placed = 0;
        while (placed < mineCount) {
            int r = uniform(0, rows, rng);
            int c = uniform(0, cols, rng);
            if (grid[r][c].mine) continue;
            // Exclude safe zone (click + neighbors)
            if (absInt(r - excludeR) <= 1 && absInt(c - excludeC) <= 1) continue;
            grid[r][c].mine = true;
            placed++;
        }
    }

    RevealResult reveal(int r, int c) {
        if (gameOver || won) return RevealResult.NO_CHANGE;
        if (r < 0 || r >= rows || c < 0 || c >= cols) return RevealResult.NO_CHANGE;
        if (grid[r][c].revealed || grid[r][c].flagged) return RevealResult.NO_CHANGE;

        if (!firstClickDone) {
            firstClickDone = true;
            placeMines(r, c);
        }

        grid[r][c].revealed = true;
        revealedCount++;

        if (grid[r][c].mine) {
            explodedRow = r;
            explodedCol = c;
            gameOver = true;
            return RevealResult.EXPLODED;
        }

        // Flood fill if 0 mines around
        if (adjacentMines(r, c) == 0) floodReveal(r, c);

        if (checkWin()) {
            won = true;
            gameOver = true;
            return RevealResult.WON;
        }
        return RevealResult.REVEALED;
    }

    void floodReveal(int r, int c) {
        // Простой BFS через массив (без GC-контейнеров для @nogc совместимости).
        // Используем стек рекурсии — поле небольшое.
        if (grid[r][c].mine) return;
        for (int dr = -1; dr <= 1; dr++)
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int nr = r + dr, nc = c + dc;
                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols &&
                    !grid[nr][nc].revealed && !grid[nr][nc].flagged && !grid[nr][nc].mine) {
                    grid[nr][nc].revealed = true;
                    revealedCount++;
                    if (adjacentMines(nr, nc) == 0) floodReveal(nr, nc);
                }
            }
    }

    bool checkWin() {
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                if (!grid[r][c].mine && !grid[r][c].revealed) return false;
        return true;
    }

    void toggleFlag(int r, int c) {
        if (gameOver || won) return;
        if (r < 0 || r >= rows || c < 0 || c >= cols) return;
        if (grid[r][c].revealed) return;
        grid[r][c].flagged = !grid[r][c].flagged;
        flaggedCount += grid[r][c].flagged ? 1 : -1;
    }

    int minesLeft() { return mineCount - flaggedCount; }
}

// ============ Main ============

int main() {
    writeln("Сапёр: Эволюция — Desktop (D + SDL2)");

    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        writeln("SDL_Init failed");
        return 1;
    }
    if (TTF_Init() != 0) {
        writeln("TTF_Init failed");
        return 1;
    }

    int rows = 16, cols = 16, mineCount = 40;
    int cellSize = 32;
    int winW = cols * cellSize;
    int winH = rows * cellSize + 60;  // +60 for HUD

    auto window = SDL_CreateWindow("Сапёр: Эволюция", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED, winW, winH, 0);
    if (window is null) {
        writeln("SDL_CreateWindow failed");
        return 1;
    }
    auto renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (renderer is null) {
        writeln("SDL_CreateRenderer failed");
        return 1;
    }

    auto engine = new Engine(rows, cols, mineCount);

    // Load textures
    SDL_Texture* texClosed = loadTexture(renderer, "assets/tile_closed.png");
    SDL_Texture* texOpen = loadTexture(renderer, "assets/tile_open.png");
    SDL_Texture* texBomb = loadTexture(renderer, "assets/tile_bomb.png");
    SDL_Texture* texFlag = loadTexture(renderer, "assets/tile_flag.png");
    SDL_Texture*[9] texNums;
    texNums[1] = loadTexture(renderer, "assets/num_1.png");
    texNums[2] = loadTexture(renderer, "assets/num_2.png");
    texNums[3] = loadTexture(renderer, "assets/num_3.png");
    texNums[4] = loadTexture(renderer, "assets/num_4.png");
    texNums[5] = loadTexture(renderer, "assets/num_5.png");
    texNums[6] = loadTexture(renderer, "assets/num_6.png");
    texNums[7] = loadTexture(renderer, "assets/num_7.png");
    texNums[8] = loadTexture(renderer, "assets/num_8.png");

    bool running = true;
    bool needsRedraw = true;
    uint lastShift = SDL_GetTicks();

    while (running) {
        ubyte[56] eventBuf;
        while (SDL_PollEvent(cast(SDL_Event*)eventBuf.ptr) != 0) {
            // First 4 bytes = type
            uint type = *cast(uint*)eventBuf.ptr;
            if (type == SDL_QUIT) {
                running = false;
            } else if (type == SDL_MOUSEBUTTONDOWN) {
                // MouseButtonEvent starts at offset 0
                auto btn = cast(SDL_MouseButtonEvent*)eventBuf.ptr;
                int x = btn.x;
                int y = btn.y;
                if (y >= 60) {
                    int c = x / cellSize;
                    int r = (y - 60) / cellSize;
                    if (r >= 0 && r < rows && c >= 0 && c < cols) {
                        if (btn.button == SDL_BUTTON_LEFT) {
                            auto res = engine.reveal(r, c);
                            if (res == RevealResult.EXPLODED) writeln("BOOM! Game over.");
                            if (res == RevealResult.WON) writeln("YOU WIN!");
                        } else if (btn.button == SDL_BUTTON_RIGHT) {
                            engine.toggleFlag(r, c);
                        }
                        needsRedraw = true;
                    }
                }
            }
        }

        if (needsRedraw) {
            SDL_SetRenderDrawColor(renderer, 16, 20, 24, 255);
            SDL_RenderClear(renderer);

            // HUD background
            SDL_SetRenderDrawColor(renderer, 34, 40, 49, 255);
            SDL_Rect hudRect = {0, 0, winW, 60};
            SDL_RenderFillRect(renderer, &hudRect);

            // Draw grid
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    SDL_Rect dst = {c * cellSize, r * cellSize + 60, cellSize, cellSize};
                    auto cell = engine.grid[r][c];
                    if (cell.revealed) {
                        if (cell.mine) {
                            if (texBomb) SDL_RenderCopy(renderer, texBomb, null, &dst);
                        } else {
                            if (texOpen) SDL_RenderCopy(renderer, texOpen, null, &dst);
                            int n = engine.adjacentMines(r, c);
                            if (n > 0 && n <= 8 && texNums[n]) {
                                SDL_RenderCopy(renderer, texNums[n], null, &dst);
                            }
                        }
                    } else if (cell.flagged) {
                        if (texFlag) SDL_RenderCopy(renderer, texFlag, null, &dst);
                    } else {
                        if (texClosed) SDL_RenderCopy(renderer, texClosed, null, &dst);
                    }
                    // Grid lines
                    SDL_SetRenderDrawColor(renderer, 16, 20, 24, 255);
                    SDL_RenderDrawRect(renderer, &dst);
                }
            }

            SDL_RenderPresent(renderer);
            needsRedraw = false;
        }
        SDL_Delay(16);
    }

    return 0;
}

SDL_Texture* loadTexture(SDL_Renderer* renderer, string path) {
    auto surface = IMG_Load(path.toStringz);
    if (surface is null) {
        writeln("Failed to load: ", path);
        return null;
    }
    auto tex = SDL_CreateTextureFromSurface(renderer, surface);
    SDL_FreeSurface(surface);
    return tex;
}

// Helper: convert D string to C string
const(char)* toStringz(string s) {
    import std.string;
    return s.toStringz;
}
