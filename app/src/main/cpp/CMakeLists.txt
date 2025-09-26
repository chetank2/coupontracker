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
    # ${MLC_LLM_INCLUDE_DIR}  # Uncomment when MLC-LLM is available
)

# Check for MLC-LLM availability and select appropriate source
option(ENABLE_REAL_MLC_LLM "Enable real MLC-LLM integration when libraries are available" ON)

# Check if we can use the real implementation
set(USE_REAL_MLC_LLM FALSE)
if(ENABLE_REAL_MLC_LLM)
    # Look for MLC-LLM headers/libraries (placeholder check)
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/mlc_llm_jni_real.cpp")
        set(USE_REAL_MLC_LLM TRUE)
        message(STATUS "Using enhanced MLC-LLM JNI implementation")
    endif()
endif()

# Source files
if(USE_REAL_MLC_LLM)
    set(SOURCES mlc_llm_jni_real.cpp)
    # Add MLC-LLM specific definitions
    add_compile_definitions(MLC_LLM_ENHANCED)
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

# Link libraries
target_link_libraries(
    mlc_llm_android
    ${log-lib}
    ${android-lib}
    # ${MLC_LLM_LIB_DIR}/libmlc_llm.so  # Uncomment when MLC-LLM is available
)

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
