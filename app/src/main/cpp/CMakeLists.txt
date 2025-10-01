# CMakeLists.txt for MiniCPM Vision JNI
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
find_library(jnigraphics-lib jnigraphics)

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Use real vision JNI implementation
set(SOURCES llama_vision_jni.cpp)
message(STATUS "Using llama.cpp vision JNI implementation")

# Optional: Link against llama.cpp if available
set(LLAMA_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
find_library(llama-lib
    NAMES llama
    PATHS ${LLAMA_LIB_DIR}
    NO_DEFAULT_PATH
)

# Create shared library
add_library(
    mlc_llm_android
    SHARED
    ${SOURCES}
)

# Link libraries
if(llama-lib)
    message(STATUS "Found llama.cpp library: ${llama-lib}")
    target_link_libraries(mlc_llm_android
        ${llama-lib}
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
    )
    target_compile_definitions(mlc_llm_android PRIVATE LLAMA_CPP_AVAILABLE)
else()
    message(STATUS "llama.cpp library not found, using simplified mode")
    target_link_libraries(mlc_llm_android
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
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
