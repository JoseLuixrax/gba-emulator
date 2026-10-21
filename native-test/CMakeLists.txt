# Desktop test harness for the native emulation core.
#
#   cmake -S native-test -B build/native-test -DMGBA_DIR=$PWD/third_party/mgba
#   cmake --build build/native-test -j
#   ./build/native-test/host_test native-test/roms build/native-test/out
cmake_minimum_required(VERSION 3.18)
project(gbaemu_host_test C)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp" core)

add_executable(host_test host_test.c)
target_link_libraries(host_test PRIVATE gbaemu)
