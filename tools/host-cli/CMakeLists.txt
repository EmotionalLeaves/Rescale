# Desktop build of the app's engine with CPU ncnn.
#   cmake -S tools/host-cli -B build/cli -Dncnn_DIR=<ncnn-install>/lib/cmake/ncnn && cmake --build build/cli
cmake_minimum_required(VERSION 3.18)
project(rescale_cli CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

find_package(ncnn REQUIRED)
find_package(ZLIB REQUIRED)
set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../engine/native/src/main/cpp)

add_executable(rescale_cli main.cpp
    ${SRC}/core/tiler.cpp ${SRC}/core/sinks.cpp ${SRC}/core/engine.cpp ${SRC}/core/postprocess.cpp ${SRC}/core/chain.cpp ${SRC}/core/transcode.cpp ${SRC}/core/faces.cpp ${SRC}/face_restorer.cpp ${SRC}/third_party/jpge/jpge.cpp ${SRC}/ncnn_runner.cpp)
target_include_directories(rescale_cli PRIVATE ${SRC})
target_link_libraries(rescale_cli PRIVATE ncnn ZLIB::ZLIB)
