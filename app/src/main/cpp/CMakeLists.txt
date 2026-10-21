# Native part of the app: the mGBA core plus our small JNI bridge.
#
# mGBA's source is not stored in this repository. Gradle downloads it into
# third_party/mgba (task :app:fetchMgba) and passes its location as MGBA_DIR.
cmake_minimum_required(VERSION 3.18)
project(gbaemu C)

if(NOT MGBA_DIR)
	set(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mgba")
endif()
get_filename_component(MGBA_DIR "${MGBA_DIR}" ABSOLUTE)
if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
	message(FATAL_ERROR "mGBA source not found in ${MGBA_DIR}. Run ./gradlew :app:fetchMgba")
endif()

# The emulator must be fast even in Debug builds, otherwise games run at a
# fraction of full speed. Keep debug info but always optimise.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")

# mGBA declares an old minimum CMake version; newer CMake needs this hint.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

# Build only libmgba: no Qt/SDL frontends, no optional dependencies.
set(M_CORE_GBA ON)
set(M_CORE_GB ON)
add_subdirectory("${MGBA_DIR}" mgba EXCLUDE_FROM_ALL)

if(ANDROID)
	add_library(gbaemu SHARED emu_core.c jni_bridge.c)
	target_link_libraries(gbaemu PRIVATE mgba log)
	# Only export JNI symbols; smaller library and faster loading.
	target_link_options(gbaemu PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
	# 16 KB page size support (required for new apps on Android 15+ devices).
	target_link_options(gbaemu PRIVATE "-Wl,-z,max-page-size=16384")
else()
	# Desktop build used by native-test/ to exercise the core without a phone.
	add_library(gbaemu STATIC emu_core.c)
	target_link_libraries(gbaemu PUBLIC mgba)
endif()

target_include_directories(gbaemu PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
# mGBA's headers expect the same feature macros its own sources get.
target_compile_definitions(gbaemu PRIVATE _GNU_SOURCE)
target_compile_options(gbaemu PRIVATE -Wall -Wextra -Wno-unused-parameter)
