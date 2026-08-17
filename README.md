# MinesweeperEvolution

Android Minesweeper game with 4 modes and 3 difficulty levels.

## Game Modes

1. **Classic** — static mines, traditional minesweeper rules
2. **Drift (~50% Random)** — every N seconds mines drift; mines under flags stay put, numbers update
3. **Chaos (~75% Random)** — every N seconds all mines relocate, flags reset
4. **Anarchy (Full Random)** — Chaos + random mine count (1 to total cells) on each shift

## Difficulties

- Beginner: 8 × 8, 10 mines
- Veteran: 16 × 16, 40 mines
- Master: 16 × 30, 99 mines

## Build

Requirements: JDK 17, Android SDK 34, Gradle 8.7.

```bash
cd minesweeper  # project root with build.gradle
gradle assembleDebug --offline
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Pre-built APK is in `release/MinesweeperEvolution.apk`.

## Features

- Custom bomb launcher icon (PNG, all densities)
- Adaptive icon support (Android 8+)
- Save / autosave on exit
- Statistics per mode & difficulty
- Settings: vibration, long-press flag, shift interval (3-30 sec)
- Safe first click (mines removed around first click)
- Numbers update after each mine shift
- Mines never spawn on opened cells
