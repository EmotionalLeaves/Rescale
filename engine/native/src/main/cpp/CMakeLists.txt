cmake_minimum_required(VERSION 3.22)
project(rescale_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ncnn prebuilt (Vulkan) is fetched by the :engine:native Gradle task `fetchNcnn`.
if(NOT NCNN_ROOT)
    message(FATAL_ERROR "NCNN_ROOT not set; run ./gradlew :engine:native:fetchNcnn")
endif()
set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(rescale SHARED
    core/tiler.cpp
    core/sinks.cpp
    core/engine.cpp
    ncnn_runner.cpp
    jni_bridge.cpp)

target_include_directories(rescale PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(rescale PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(rescale PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(rescale ncnn jnigraphics log z android)
