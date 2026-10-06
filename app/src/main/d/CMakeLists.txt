# CMakeLists.txt для сборки D-движка через LDC2
# Интегрируется в Android Gradle через externalNativeBuild

cmake_minimum_required(VERSION 3.10)
project(minesweeper_engine LANGUAGES C)

# Путь к LDC2 (должен быть установлен)
set(LDC2_BIN "ldc2" CACHE STRING "Path to LDC2 compiler")

# Исходники D
set(D_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/engine.d
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge.d
)

# Выходная директория для .so
set(SO_OUTPUT ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libminesweeper_engine.so)

# NDK sysroot
if(ANDROID)
    set(NDK_TOOLCHAIN "$ENV{ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64")
    set(ANDROID_SYSROOT "${NDK_TOOLCHAIN}/sysroot")
    set(CLANG_TARGET "aarch64-linux-android${ANDROID_PLATFORM_MINIMUM_VERSION}")
    
    # Компиляция D в объектный файл
    add_custom_command(
        OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/engine_d.o
        COMMAND ${LDC2_BIN}
            -mtriple=${CLANG_TARGET}
            -betterC
            -c
            -I${CMAKE_CURRENT_SOURCE_DIR}
            ${D_SOURCES}
            -of${CMAKE_CURRENT_BINARY_DIR}/engine_d.o
        DEPENDS ${D_SOURCES}
        COMMENT "Compiling D sources with LDC2"
    )
    
    # Линковка в .so
    add_custom_command(
        OUTPUT ${SO_OUTPUT}
        COMMAND ${LDC2_BIN}
            -mtriple=${CLANG_TARGET}
            -betterC
            -L-L${NDK_TOOLCHAIN}/bin
            -L--target=${CLANG_TARGET}
            -L--sysroot=${ANDROID_SYSROOT}
            -shared
            ${CMAKE_CURRENT_BINARY_DIR}/engine_d.o
            -of${SO_OUTPUT}
            -L-lc -L-lm
        DEPENDS ${CMAKE_CURRENT_BINARY_DIR}/engine_d.o
        COMMENT "Linking libminesweeper_engine.so"
    )
    
    # Цель сборки
    add_custom_target(minesweeper_engine ALL
        DEPENDS ${SO_OUTPUT}
    )
else()
    message(WARNING "Android NDK not detected. D engine will not be compiled.")
endif()
