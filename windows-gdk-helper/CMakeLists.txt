cmake_minimum_required(VERSION 3.24)
project(NovaBroadcastGdkHelper LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "NovaBroadcastGdkHelper must be built on Windows with the Microsoft GDK installed.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(NovaBroadcastGdkHelper main.cpp)
target_compile_definitions(NovaBroadcastGdkHelper PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(NovaBroadcastGdkHelper PRIVATE xgameruntime winhttp)

# The Microsoft GDK command prompt/toolchain supplies the XGameRuntime headers and
# xgameruntime import library. Configure/build this project from a GDK command prompt.
