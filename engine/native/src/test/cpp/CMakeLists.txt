# Host build of the ncnn-free engine core + tests.
cmake_minimum_required(VERSION 3.18)
project(rescale_engine_tests CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(ZLIB REQUIRED)
set(CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/core)

add_executable(engine_test engine_test.cpp ${CORE}/tiler.cpp ${CORE}/sinks.cpp ${CORE}/engine.cpp ${CORE}/postprocess.cpp ${CORE}/chain.cpp ${CORE}/transcode.cpp ${CORE}/../third_party/jpge/jpge.cpp)
target_include_directories(engine_test PRIVATE ${CORE})
target_link_libraries(engine_test PRIVATE ZLIB::ZLIB)
target_compile_options(engine_test PRIVATE -Wall -Wextra -O2 -fsanitize=address,undefined)
target_link_options(engine_test PRIVATE -fsanitize=address,undefined)

enable_testing()
add_test(NAME engine_test COMMAND engine_test ${CMAKE_CURRENT_BINARY_DIR})
