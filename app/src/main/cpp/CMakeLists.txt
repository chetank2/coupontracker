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

# MLC-LLM library path (to be updated when actual library is available)
# set(MLC_LLM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/mlc-llm")
# set(MLC_LLM_INCLUDE_DIR "${MLC_LLM_ROOT}/include")
# set(MLC_LLM_LIB_DIR "${MLC_LLM_ROOT}/lib/${ANDROID_ABI}")

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Check for MLC-LLM availability and select appropriate source
option(ENABLE_REAL_MLC_LLM "Enable real MLC-LLM integration when libraries are available" ON)

# Default to mock JNI implementation unless headers and libraries are found
set(USE_REAL_MLC_LLM FALSE)
set(MLC_LLM_INCLUDE_DIRS "")
set(MLC_LLM_LINK_LIBS "")

if(ENABLE_REAL_MLC_LLM)
    set(MLC_LLM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/mlc_llm" CACHE PATH "MLC-LLM installation path")
    set(MLC_LLM_LIB_DIR "${MLC_LLM_ROOT}/lib/${ANDROID_ABI}")

    find_path(MLC_LLM_MAIN_INCLUDE
        NAMES mlc/runtime/c_runtime_api.h
        PATHS ${MLC_LLM_ROOT}/include
    )

    find_path(MLC_LLM_TVM_INCLUDE
        NAMES tvm/runtime/module.h
        PATHS ${MLC_LLM_ROOT}/3rdparty/tvm/include
    )

    find_path(MLC_LLM_DLPACK_INCLUDE
        NAMES dlpack/dlpack.h
        PATHS ${MLC_LLM_ROOT}/3rdparty/dlpack/include ${MLC_LLM_ROOT}/include
    )

    find_library(MLC_LLM_RUNTIME_LIB
        NAMES mlc_llm_runtime
        PATHS ${MLC_LLM_LIB_DIR} ${MLC_LLM_ROOT}/lib
        NO_DEFAULT_PATH
    )

    find_library(TVM_RUNTIME_LIB
        NAMES tvm_runtime
        PATHS ${MLC_LLM_LIB_DIR} ${MLC_LLM_ROOT}/lib
        NO_DEFAULT_PATH
    )

    find_library(RELAX_RUNTIME_LIB
        NAMES relax_runtime
        PATHS ${MLC_LLM_LIB_DIR} ${MLC_LLM_ROOT}/lib
        NO_DEFAULT_PATH
    )

    if(MLC_LLM_MAIN_INCLUDE AND MLC_LLM_RUNTIME_LIB)
        set(USE_REAL_MLC_LLM TRUE)
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

        message(STATUS "Using real MLC-LLM JNI implementation")
    else()
        message(WARNING "MLC-LLM headers or libraries not found; falling back to mock JNI implementation")
    endif()
endif()

# Source files
if(USE_REAL_MLC_LLM)
    set(SOURCES mlc_llm_jni_real.cpp)
else()
    set(SOURCES mlc_llm_jni.cpp)
    message(STATUS "Using mock MLC-LLM JNI implementation")
endif()

# Create shared library
add_library(
    mlc_llm_android
    SHARED
    ${SOURCES}
)

# MLC-LLM library configuration
if(USE_REAL_MLC_LLM)
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
