cmake_minimum_required(VERSION 3.22)
project(rescale_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ncnn comes either from source (NCNN_SOURCE_DIR: reproducible, what F-Droid uses) or from
# the pinned prebuilt release fetched by the Gradle task `fetchNcnn` (NCNN_ROOT: fast).
if(NCNN_SOURCE_DIR)
    set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
    set(NCNN_SHARED_LIB OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
    set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(NCNN_DISABLE_RTTI OFF CACHE BOOL "" FORCE)
    set(NCNN_DISABLE_EXCEPTION OFF CACHE BOOL "" FORCE)
    add_subdirectory(${NCNN_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/ncnn EXCLUDE_FROM_ALL)
elseif(NCNN_ROOT)
    set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
    find_package(ncnn REQUIRED)
else()
    message(FATAL_ERROR "Set NCNN_ROOT (./gradlew :engine:native:fetchNcnn) or NCNN_SOURCE_DIR (-PncnnSource=...)")
endif()

add_library(rescale SHARED
    core/tiler.cpp
    core/sinks.cpp
    core/engine.cpp
    core/postprocess.cpp
    core/chain.cpp
    core/transcode.cpp
    third_party/jpge/jpge.cpp
    ncnn_runner.cpp
    jni_bridge.cpp)

target_include_directories(rescale PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(rescale PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(rescale PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(rescale ncnn jnigraphics log z android)
