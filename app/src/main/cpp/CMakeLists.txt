# CMakeLists.txt for MiniCPM Vision JNI Bridge
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
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/native_bridge
)

# Backend selection:
#   -DUSE_MLC=ON    (default, vision-capable via dynamic runtime loading)
#   -DUSE_LLAMA=ON  (text-only for now, vision TODO)
if (NOT DEFINED USE_MLC AND NOT DEFINED USE_LLAMA)
    set(USE_MLC ON)  # Default to MLC since it supports vision
    message(STATUS "No backend specified, defaulting to MLC-LLM")
endif()

if (USE_MLC)
    message(STATUS "✅ Building with MLC-LLM backend (vision-capable)")
    add_definitions(-DUSE_MLC)
elseif (USE_LLAMA)
    message(STATUS "✅ Building with llama.cpp backend (text-only, vision TODO)")
    add_definitions(-DUSE_LLAMA)
endif()

# Create shared library
add_library(
    mlc_llm_android
    SHARED
    native_bridge/MlcLlmNativeBridge.cpp
)

# Link Android libraries
target_link_libraries(mlc_llm_android
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)

# If using llama.cpp, link against prebuilt libllama.so
# (place libllama.so in app/src/main/jniLibs/<abi>/)
if (USE_LLAMA)
    set(LLAMA_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
    find_library(llama-lib
        NAMES llama
        PATHS ${LLAMA_LIB_DIR}
        NO_DEFAULT_PATH
    )
    
    if(llama-lib)
        message(STATUS "Found llama.cpp library: ${llama-lib}")
        target_link_libraries(mlc_llm_android ${llama-lib})
        target_compile_definitions(mlc_llm_android PRIVATE LLAMA_CPP_AVAILABLE)
    else()
        message(WARNING "llama.cpp library not found in ${LLAMA_LIB_DIR}")
        message(WARNING "Build will succeed but runtime will fail")
        message(WARNING "Place libllama.so in app/src/main/jniLibs/${ANDROID_ABI}/")
    endif()
endif()

# If using MLC-LLM, we dlopen() the runtime from model directory at runtime
# No static linking needed - the runtime .so is shipped with the model
if (USE_MLC)
    message(STATUS "MLC-LLM runtime will be loaded dynamically from model directory")
    target_link_libraries(mlc_llm_android
        dl  # For dlopen/dlsym
    )
endif()

# Compiler flags
target_compile_options(
    mlc_llm_android
    PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fvisibility=hidden
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

# Print configuration summary
message(STATUS "==============================================")
message(STATUS "Configuration Summary:")
message(STATUS "  Backend: ${USE_MLC}${USE_LLAMA}")
message(STATUS "  ABI: ${ANDROID_ABI}")
message(STATUS "  Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  C++ standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "==============================================")
