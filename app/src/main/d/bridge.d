/**
 * bridge.d — JNI мост между D-движком и Kotlin UI
 *
 * Экспортирует функции с JNI-именами для NativeBridge.kt.
 * @nogc — не использует D GC.
 */
module bridge;

import engine;
import core.stdc.stdlib : malloc;
import core.stdc.string : memcpy, memset;

extern(C):

// Глобальный указатель на движок (одна игра за раз)
__gshared GameEngine* g_engine;

// ===== JNI-экспорты =====

// Java_com_zminesweeper_game_NativeBridge_nativeInit
export void Java_com_zminesweeper_game_NativeBridge_nativeInit(
    JNIEnv* env, jobject thiz,
    jint mode, jint rows, jint cols, jint mineCount
) {
    // Инициализируем арену (1 МБ достаточно для любого поля)
    if (!g_arena) {
        arena_init(1024 * 1024);
    }
    arena_reset();
    
    // Выделяем движок в арене
    g_engine = cast(GameEngine*) arena_alloc(GameEngine.sizeof);
    memset(g_engine, 0, GameEngine.sizeof);
    
    g_engine.rngState = cast(uint)(rows * cols * 31 + mineCount * 17 + 1);
    
    engine_init(g_engine, cast(GameMode)mode, rows, cols, mineCount);
}

// Java_com_zminesweeper_game_NativeBridge_nativeReveal
export jint Java_com_zminesweeper_game_NativeBridge_nativeReveal(
    JNIEnv* env, jobject thiz, jint row, jint col
) {
    if (!g_engine) return 0;
    return cast(jint) engine_reveal(g_engine, row, col);
}

// Java_com_zminesweeper_game_NativeBridge_nativeToggleFlag
export jboolean Java_com_zminesweeper_game_NativeBridge_nativeToggleFlag(
    JNIEnv* env, jobject thiz, jint row, jint col
) {
    if (!g_engine) return 0;
    return engine_toggleFlag(g_engine, row, col);
}

// Java_com_zminesweeper_game_NativeBridge_nativeShiftMines
export void Java_com_zminesweeper_game_NativeBridge_nativeShiftMines(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return;
    engine_shiftMines(g_engine);
}

// Java_com_zminesweeper_game_NativeBridge_nativeIsMine
export jboolean Java_com_zminesweeper_game_NativeBridge_nativeIsMine(
    JNIEnv* env, jobject thiz, jint row, jint col
) {
    if (!g_engine) return 0;
    return engine_isMine(g_engine, row, col);
}

// Java_com_zminesweeper_game_NativeBridge_nativeIsRevealed
export jboolean Java_com_zminesweeper_game_NativeBridge_nativeIsRevealed(
    JNIEnv* env, jobject thiz, jint row, jint col
) {
    if (!g_engine) return 0;
    return engine_isRevealed(g_engine, row, col);
}

// Java_com_zminesweeper_game_NativeBridge_nativeIsFlagged
export jboolean Java_com_zminesweeper_game_NativeBridge_nativeIsFlagged(
    JNIEnv* env, jobject thiz, jint row, jint col
) {
    if (!g_engine) return 0;
    return engine_isFlagged(g_engine, row, col);
}

// Java_com_zminesweeper_game_NativeBridge_nativeAdjacentMines
export jint Java_com_zminesweeper_game_NativeBridge_nativeAdjacentMines(
    JNIEnv* env, jobject thiz, jint row, jint col
) {
    if (!g_engine) return 0;
    return engine_adjacentMines(g_engine, row, col);
}

// Java_com_zminesweeper_game_NativeBridge_nativeGetMinesLeft
export jint Java_com_zminesweeper_game_NativeBridge_nativeGetMinesLeft(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return 0;
    return engine_minesLeft(g_engine);
}

// Java_com_zminesweeper_game_NativeBridge_nativeIsGameOver
export jboolean Java_com_zminesweeper_game_NativeBridge_nativeIsGameOver(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return 0;
    return g_engine.gameOver;
}

// Java_com_zminesweeper_game_NativeBridge_nativeIsWon
export jboolean Java_com_zminesweeper_game_NativeBridge_nativeIsWon(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return 0;
    return g_engine.won;
}

// Java_com_zminesweeper_game_NativeBridge_nativeGetExplodedRow
export jint Java_com_zminesweeper_game_NativeBridge_nativeGetExplodedRow(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return -1;
    return g_engine.explodedRow;
}

// Java_com_zminesweeper_game_NativeBridge_nativeGetExplodedCol
export jint Java_com_zminesweeper_game_NativeBridge_nativeGetExplodedCol(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return -1;
    return g_engine.explodedCol;
}

// Java_com_zminesweeper_game_NativeBridge_nativeGetShiftsCount
export jint Java_com_zminesweeper_game_NativeBridge_nativeGetShiftsCount(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return 0;
    return g_engine.shiftsCount;
}

// Java_com_zminesweeper_game_NativeBridge_nativeGetRows
export jint Java_com_zminesweeper_game_NativeBridge_nativeGetRows(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return 0;
    return g_engine.rows;
}

// Java_com_zminesweeper_game_NativeBridge_nativeGetCols
export jint Java_com_zminesweeper_game_NativeBridge_nativeGetCols(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return 0;
    return g_engine.cols;
}

// Java_com_zminesweeper_game_NativeBridge_nativeGetMineCount
export jint Java_com_zminesweeper_game_NativeBridge_nativeGetMineCount(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return 0;
    return g_engine.mineCount;
}

// Java_com_zminesweeper_game_NativeBridge_nativeIsFirstClickDone
export jboolean Java_com_zminesweeper_game_NativeBridge_nativeIsFirstClickDone(
    JNIEnv* env, jobject thiz
) {
    if (!g_engine) return 0;
    return g_engine.firstClickDone;
}

// ===== JNI типы (заглушки — настоящие из jni.h) =====

alias jint = int;
alias jboolean = ubyte;
alias JNIEnv = void;
alias jobject = void*;

// Нужны реальные JNI типы — подключаем jni.h
import core.stdc.config : c_long;

// В реальной сборке LDC2 подключит jni.h из NDK
// Здесь — псевдонимы для компиляции
