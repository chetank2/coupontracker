# CMakeLists.txt for MLC-LLM Android JNI
cmake_minimum_required(VERSION 3.18.1)

project("mlc_llm_android")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable optimization for release builds
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O0 -g")

# Find required Android libraries
find_library(log-lib log)
find_library(android-lib android)

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Control whether to build with the mock JNI implementation
option(BUILD_MOCK_JNI "Build the mock JNI bridge instead of the real MLC-LLM integration" OFF)

set(MLC_LLM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/mlc_llm" CACHE PATH "MLC-LLM artifact root")
set(MLC_LLM_INCLUDE_DIRS "")
set(MLC_LLM_LINK_LIBS "")

if(NOT BUILD_MOCK_JNI)
    set(MLC_LLM_LIB_DIR "${MLC_LLM_ROOT}/lib/${ANDROID_ABI}")

    if(NOT EXISTS "${MLC_LLM_ROOT}")
        message(FATAL_ERROR "MLC_LLM_ROOT not found at ${MLC_LLM_ROOT}. Populate the directory with MLC-LLM headers and libraries or set BUILD_MOCK_JNI=ON to use the mock implementation.")
    endif()

    find_path(MLC_LLM_MAIN_INCLUDE
        NAMES mlc/runtime/c_runtime_api.h
        HINTS ${MLC_LLM_ROOT}/include
        NO_DEFAULT_PATH
    )

    find_path(MLC_LLM_TVM_INCLUDE
        NAMES tvm/runtime/module.h
        HINTS ${MLC_LLM_ROOT}/3rdparty/tvm/include
        NO_DEFAULT_PATH
    )

    find_path(MLC_LLM_DLPACK_INCLUDE
        NAMES dlpack/dlpack.h
        HINTS ${MLC_LLM_ROOT}/3rdparty/dlpack/include ${MLC_LLM_ROOT}/include
        NO_DEFAULT_PATH
    )

    find_library(MLC_LLM_RUNTIME_LIB
        NAMES mlc_llm_runtime
        HINTS ${MLC_LLM_LIB_DIR}
        NO_DEFAULT_PATH
    )

    find_library(TVM_RUNTIME_LIB
        NAMES tvm_runtime
        HINTS ${MLC_LLM_LIB_DIR}
        NO_DEFAULT_PATH
    )

    find_library(RELAX_RUNTIME_LIB
        NAMES relax_runtime
        HINTS ${MLC_LLM_LIB_DIR}
        NO_DEFAULT_PATH
    )

    if(MLC_LLM_MAIN_INCLUDE AND MLC_LLM_RUNTIME_LIB)
        list(APPEND MLC_LLM_INCLUDE_DIRS ${MLC_LLM_MAIN_INCLUDE})
        if(MLC_LLM_TVM_INCLUDE)
            list(APPEND MLC_LLM_INCLUDE_DIRS ${MLC_LLM_TVM_INCLUDE})
        endif()
        if(MLC_LLM_DLPACK_INCLUDE)
            list(APPEND MLC_LLM_INCLUDE_DIRS ${MLC_LLM_DLPACK_INCLUDE})
        endif()

        list(APPEND MLC_LLM_LINK_LIBS ${MLC_LLM_RUNTIME_LIB})
        if(TVM_RUNTIME_LIB)
            list(APPEND MLC_LLM_LINK_LIBS ${TVM_RUNTIME_LIB})
        endif()
        if(RELAX_RUNTIME_LIB)
            list(APPEND MLC_LLM_LINK_LIBS ${RELAX_RUNTIME_LIB})
        endif()
    else()
        message(FATAL_ERROR "Missing MLC-LLM headers or libraries in ${MLC_LLM_ROOT}. Provide the artifacts or configure BUILD_MOCK_JNI=ON.")
    endif()
endif()

# Source files
if(BUILD_MOCK_JNI)
    set(SOURCES mlc_llm_jni.cpp)
    message(STATUS "Using mock MLC-LLM JNI implementation")
else()
    set(SOURCES mlc_llm_jni_real.cpp)
    message(STATUS "Using real MLC-LLM JNI implementation")
endif()

# Create shared library
add_library(
    mlc_llm_android
    SHARED
    ${SOURCES}
)

# MLC-LLM library configuration
if(NOT BUILD_MOCK_JNI)
    target_compile_definitions(mlc_llm_android PRIVATE MLC_LLM_AVAILABLE)

    if(MLC_LLM_INCLUDE_DIRS)
        target_include_directories(mlc_llm_android PRIVATE ${MLC_LLM_INCLUDE_DIRS})
    endif()

    target_link_libraries(mlc_llm_android
        ${log-lib}
        ${android-lib}
        ${MLC_LLM_LINK_LIBS}
    )
else()
    # Standard linking for mock implementation
    target_link_libraries(mlc_llm_android
        ${log-lib}
        ${android-lib}
    )
endif()

# Compiler definitions
target_compile_definitions(
    mlc_llm_android
    PRIVATE
    ANDROID_PLATFORM
)

# Additional compiler flags
target_compile_options(
    mlc_llm_android
    PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)

# Strip symbols in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(
        TARGET mlc_llm_android
        POST_BUILD
        COMMAND ${CMAKE_STRIP} $<TARGET_FILE:mlc_llm_android>
    )
endif()
