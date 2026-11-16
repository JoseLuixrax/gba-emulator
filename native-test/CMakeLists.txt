# Desktop test harness for the native emulation core.
#
#   cmake -S native-test -B build/native-test -DMGBA_DIR=$PWD/third_party/mgba -DMELONDS_DIR=$PWD/third_party/melonds
#   cmake --build build/native-test -j
#   ./build/native-test/host_test native-test/roms build/native-test/out
#   ./build/native-test/nds_host_test native-test/nds-roms/test.nds build/native-test/out-nds
cmake_minimum_required(VERSION 3.18)
project(gbaemu_host_test C CXX)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp" core)

add_executable(host_test host_test.c)
target_link_libraries(host_test PRIVATE gbaemu)

add_executable(nds_host_test nds_host_test.cpp)
target_link_libraries(nds_host_test PRIVATE ndsemu)
