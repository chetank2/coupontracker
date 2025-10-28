cmake_minimum_required(VERSION 3.18.1)

project("mlc_llm_android")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(USE_MLC_RUNTIME "Build JNI bridge against the MLC runtime" OFF)
option(USE_LLAMACPP_RUNTIME "Build JNI bridge against llama.cpp runtime" ON)

if(USE_MLC_RUNTIME AND USE_LLAMACPP_RUNTIME)
    message(FATAL_ERROR "Select only one backend: USE_MLC_RUNTIME or USE_LLAMACPP_RUNTIME")
endif()

if(NOT USE_MLC_RUNTIME AND NOT USE_LLAMACPP_RUNTIME)
    message(STATUS "No backend explicitly selected, defaulting to llama.cpp runtime")
    set(USE_LLAMACPP_RUNTIME ON)
endif()

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(dl-lib dl)

set(MLC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../libs/mlc_llm")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/llama
    ${MLC_ROOT}/include
    ${MLC_ROOT}/3rdparty/dlpack/include
    ${MLC_ROOT}/3rdparty/tvm/include
)

if(USE_MLC_RUNTIME)
    set(LLM_BACKEND_NAME "MLC runtime")
    set(JNI_SOURCE_FILE mlc_llm_jni.cpp)
else()
    set(LLM_BACKEND_NAME "llama.cpp runtime")
    set(JNI_SOURCE_FILE mlc_llm_jni_real.cpp)
endif()

message(STATUS "==============================")
message(STATUS "Building JNI bridge")
message(STATUS "  ABI: ${ANDROID_ABI}")
message(STATUS "  Backend: ${LLM_BACKEND_NAME}")
message(STATUS "  Source: ${JNI_SOURCE_FILE}")
message(STATUS "==============================")

add_library(
    mlc_llm_android
    SHARED
    ${JNI_SOURCE_FILE}
)

if(USE_LLAMACPP_RUNTIME)
    set(JNI_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

    set(llama_lib "${JNI_LIBS_DIR}/libllama.so")
    set(ggml_base_lib "${JNI_LIBS_DIR}/libggml-base.so")
    set(ggml_cpu_lib "${JNI_LIBS_DIR}/libggml-cpu.so")
    set(mtmd_lib "${JNI_LIBS_DIR}/libmtmd.so")
    set(omp_lib "${JNI_LIBS_DIR}/libomp.so")
    set(llama_android_lib "${JNI_LIBS_DIR}/libllama-android.so")

    if(NOT EXISTS "${llama_lib}")
        message(FATAL_ERROR "Required llama.cpp dependency not found: ${llama_lib}")
    endif()
    if(NOT EXISTS "${ggml_base_lib}")
        message(FATAL_ERROR "Required llama.cpp dependency not found: ${ggml_base_lib}")
    endif()
    if(NOT EXISTS "${ggml_cpu_lib}")
        message(FATAL_ERROR "Required llama.cpp dependency not found: ${ggml_cpu_lib}")
    endif()
    if(NOT EXISTS "${mtmd_lib}")
        message(WARNING "libmtmd.so not found for ${ANDROID_ABI}; vision projector support will be unavailable")
        set(mtmd_lib "")
    endif()
    if(NOT EXISTS "${omp_lib}")
        set(omp_lib "")
    endif()
    if(NOT EXISTS "${llama_android_lib}")
        set(llama_android_lib "")
    endif()

    target_link_libraries(
        mlc_llm_android
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        ${dl-lib}
        ${llama_lib}
        ${ggml_base_lib}
        ${ggml_cpu_lib}
    )

    if(mtmd_lib AND NOT mtmd_lib STREQUAL "")
        target_link_libraries(mlc_llm_android ${mtmd_lib})
    endif()
    if(omp_lib AND NOT omp_lib STREQUAL "")
        target_link_libraries(mlc_llm_android ${omp_lib})
    endif()
    if(llama_android_lib AND NOT llama_android_lib STREQUAL "")
        target_link_libraries(mlc_llm_android ${llama_android_lib})
    endif()

    target_compile_definitions(mlc_llm_android PRIVATE
        LLAMA_RUNTIME_ENABLED=1
    )
else()
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
endif()

target_compile_options(
    mlc_llm_android
    PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fvisibility=hidden
)
