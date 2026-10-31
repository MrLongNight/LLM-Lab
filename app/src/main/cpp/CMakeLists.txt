cmake_minimum_required(VERSION 3.22.1)
project("llmlab_native" LANGUAGES C CXX)

# llama.cpp is pinned as a Git submodule. Only its CPU backend is built.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(
    LLAMA_CPP_SOURCE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp"
    ABSOLUTE
)

if(NOT EXISTS "${LLAMA_CPP_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR
        "Pinned llama.cpp sources are missing. Run: git submodule update --init --recursive")
endif()

add_subdirectory("${LLAMA_CPP_SOURCE}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(llmlab_native SHARED llmlab_native.cpp)
target_include_directories(llmlab_native PRIVATE "${LLAMA_CPP_SOURCE}/include")
target_compile_features(llmlab_native PRIVATE cxx_std_17)

find_library(log-lib log)
target_link_libraries(llmlab_native PRIVATE llama ${log-lib})
