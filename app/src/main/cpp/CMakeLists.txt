cmake_minimum_required(VERSION 3.22.1)
project(vocalstage_native)

find_package(oboe REQUIRED CONFIG)

add_library(vocalstage_native SHARED
    vocal_native_engine.cpp
)

target_compile_options(vocalstage_native PRIVATE -O3 -ffast-math -Wall)

target_link_libraries(vocalstage_native
    oboe::oboe
    log
    android
)
