cmake_minimum_required(VERSION 3.22.1)
project("llmlab_native")

add_library(
    llmlab_native
    SHARED
    llmlab_native.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    llmlab_native
    ${log-lib}
)
