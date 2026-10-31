cmake_minimum_required(VERSION 3.31.6)
project("llmlab_native" LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-O3 -DNDEBUG)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
else()
    message(FATAL_ERROR "Unsupported Android ABI for LLM Lab: ${ANDROID_ABI}")
endif()
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
get_filename_component(LLAMA_CPP_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp" ABSOLUTE)
if(NOT EXISTS "${LLAMA_CPP_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "Pinned llama.cpp sources are missing. Run git submodule update --init --recursive")
endif()
# Android's CMake toolchain restricts package lookup to the Android sysroot.
# SPIRV-Headers is a host-only, header-only dependency used while compiling
# ggml-vulkan. Resolve Debian/Ubuntu's host package explicitly for cross builds.
if(ANDROID AND (NOT DEFINED SPIRV-Headers_DIR OR "${SPIRV-Headers_DIR}" MATCHES "-NOTFOUND$"))
    find_file(LLMLAB_SPIRV_HEADERS_CONFIG
        NAMES SPIRV-HeadersConfig.cmake
        PATHS
            /usr/share/cmake/SPIRV-Headers
            /usr/local/share/cmake/SPIRV-Headers
        NO_DEFAULT_PATH
        NO_CMAKE_FIND_ROOT_PATH
    )
    if(LLMLAB_SPIRV_HEADERS_CONFIG)
        get_filename_component(
            LLMLAB_SPIRV_HEADERS_CONFIG_DIR
            "${LLMLAB_SPIRV_HEADERS_CONFIG}"
            DIRECTORY
        )
        set(
            SPIRV-Headers_DIR
            "${LLMLAB_SPIRV_HEADERS_CONFIG_DIR}"
            CACHE PATH "Host SPIRV-Headers CMake package directory" FORCE
        )
    endif()
endif()

# Android's NDK does not ship Vulkan-Hpp. The host libvulkan-dev package
# provides these header-only bindings. Stage only Vulkan headers (not /usr/include)
# into an isolated include root so host libc headers cannot shadow the Android NDK.
if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android")
    find_path(LLMLAB_VULKAN_HOST_INCLUDE_DIR
        NAMES vulkan/vulkan.hpp
        PATHS /usr/include /usr/local/include
        NO_DEFAULT_PATH
        NO_CMAKE_FIND_ROOT_PATH
    )
    if(NOT LLMLAB_VULKAN_HOST_INCLUDE_DIR)
        message(FATAL_ERROR "Vulkan-Hpp headers not found. Install the host libvulkan-dev package.")
    endif()
    set(LLMLAB_VULKAN_STAGED_INCLUDE_DIR "${CMAKE_BINARY_DIR}/llmlab-vulkan-host-headers")
    file(MAKE_DIRECTORY "${LLMLAB_VULKAN_STAGED_INCLUDE_DIR}")
    file(COPY "${LLMLAB_VULKAN_HOST_INCLUDE_DIR}/vulkan" DESTINATION "${LLMLAB_VULKAN_STAGED_INCLUDE_DIR}")
    if(EXISTS "${LLMLAB_VULKAN_HOST_INCLUDE_DIR}/vk_video")
        file(COPY "${LLMLAB_VULKAN_HOST_INCLUDE_DIR}/vk_video" DESTINATION "${LLMLAB_VULKAN_STAGED_INCLUDE_DIR}")
    endif()
    if(NOT EXISTS "${LLMLAB_VULKAN_STAGED_INCLUDE_DIR}/vulkan/vulkan.hpp")
        message(FATAL_ERROR "Failed to stage Vulkan-Hpp headers for the Android build.")
    endif()
    include_directories(SYSTEM BEFORE "${LLMLAB_VULKAN_STAGED_INCLUDE_DIR}")
    message(STATUS "LLM Lab: staged Vulkan-Hpp headers for Android at ${LLMLAB_VULKAN_STAGED_INCLUDE_DIR}")
endif()

add_subdirectory("${LLAMA_CPP_SOURCE}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)
add_library(llmlab_native SHARED llmlab_native.cpp)
target_include_directories(llmlab_native PRIVATE "${LLAMA_CPP_SOURCE}/include" "${LLAMA_CPP_SOURCE}/ggml/include")
target_compile_features(llmlab_native PRIVATE cxx_std_17)
find_library(log-lib log REQUIRED)
target_link_libraries(llmlab_native PRIVATE llama ${log-lib})
