# CMakeLists.txt for Real llama.cpp Integration
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
    ${CMAKE_CURRENT_SOURCE_DIR}/llama
    ${CMAKE_CURRENT_SOURCE_DIR}/llama_cpp  # ⭐ NEW: For tools/mtmd/clip.h access
)

message(STATUS "🚀 Building REAL llama.cpp JNI bridge")
message(STATUS "   ABI: ${ANDROID_ABI}")
message(STATUS "   Build type: ${CMAKE_BUILD_TYPE}")

# Define path to prebuilt libraries
set(LLAMA_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(llama-lib "${LLAMA_LIB_DIR}/libllama.so")
set(mtmd-lib "${LLAMA_LIB_DIR}/libmtmd.so")  # ⭐ NEW: MTMD multimodal library

message(STATUS "   Looking for libraries in: ${LLAMA_LIB_DIR}")

# Check if libllama.so exists
if(EXISTS "${llama-lib}")
    message(STATUS "   ✅ Found libllama.so: ${llama-lib}")
else()
    message(FATAL_ERROR "   ❌ libllama.so not found at ${llama-lib}")
endif()

# ⭐ Check if libmtmd.so exists
if(EXISTS "${mtmd-lib}")
    message(STATUS "   ✅ Found libmtmd.so: ${mtmd-lib}")
else()
    message(FATAL_ERROR "   ❌ libmtmd.so not found at ${mtmd-lib}")
endif()

# Import libllama as prebuilt library
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION "${llama-lib}"
)

# ⭐ Import libmtmd as prebuilt library
add_library(mtmd SHARED IMPORTED)
set_target_properties(mtmd PROPERTIES
    IMPORTED_LOCATION "${mtmd-lib}"
)

# Create shared library with REAL implementation
add_library(
    mlc_llm_android
    SHARED
    mlc_llm_jni_real.cpp
)

# Link libraries
target_link_libraries(mlc_llm_android
    llama
    mtmd        # ⭐ NEW: Link MTMD for vision support
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)

# Compiler flags
target_compile_options(
    mlc_llm_android
    PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fvisibility=hidden
)

# Define that we're using real llama.cpp
target_compile_definitions(mlc_llm_android PRIVATE 
    LLAMA_CPP_REAL_INFERENCE
    REAL_JNI
)

# Strip symbols in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(
        TARGET mlc_llm_android
        POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:mlc_llm_android>
        COMMENT "Stripping debug symbols from release build"
    )
endif()

message(STATUS "==============================================")
message(STATUS "✅ Configuration complete - REAL inference enabled")
message(STATUS "==============================================")
