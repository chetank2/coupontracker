cmake_minimum_required(VERSION 3.18.1)

project("mlc_llm_android")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(dl-lib dl)

set(MLC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../libs/mlc_llm")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${MLC_ROOT}/include
    ${MLC_ROOT}/3rdparty/dlpack/include
    ${MLC_ROOT}/3rdparty/tvm/include
)

message(STATUS "==============================")
message(STATUS "Building MLC JNI bridge")
message(STATUS "  ABI: ${ANDROID_ABI}")
message(STATUS "  Source: mlc_llm_jni.cpp")
message(STATUS "==============================")

add_library(
    mlc_llm_android
    SHARED
    mlc_llm_jni.cpp
)

target_link_libraries(
    mlc_llm_android
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${dl-lib}
)

target_compile_definitions(mlc_llm_android PRIVATE
    MLC_RUNTIME_ENABLED=1
)

target_compile_options(
    mlc_llm_android
    PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fvisibility=hidden
)

