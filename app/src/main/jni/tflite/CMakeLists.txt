cmake_minimum_required(VERSION 3.10)

project(TFLiteEngine VERSION 1.0)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(audioEngine SHARED
        TFLiteEngine.cpp
        TFLiteEngineJNI.cpp
)

target_include_directories(audioEngine PRIVATE ${CMAKE_CURRENT_LIST_DIR})

target_link_libraries(audioEngine log)
