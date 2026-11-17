# Native part of the app: two emulation cores, each in its own library so
# they never share symbols.
#   libgbaemu.so  mGBA (GBA, GB, GBC) + emu_core.c / jni_bridge.c
#   libndsemu.so  melonDS (DS)        + nds/
#
# Neither core is stored in this repository. Gradle downloads them into
# third_party/ while configuring the project and passes MGBA_DIR / MELONDS_DIR.
cmake_minimum_required(VERSION 3.18)
project(gbaemu C CXX)

if(NOT MGBA_DIR)
	set(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mgba")
endif()
get_filename_component(MGBA_DIR "${MGBA_DIR}" ABSOLUTE)
if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
	message(FATAL_ERROR "mGBA source not found in ${MGBA_DIR}. Run any Gradle build (e.g. ./gradlew help): app/build.gradle.kts downloads it.")
endif()
if(NOT MELONDS_DIR)
	set(MELONDS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/melonds")
endif()
get_filename_component(MELONDS_DIR "${MELONDS_DIR}" ABSOLUTE)
if(NOT EXISTS "${MELONDS_DIR}/src/NDS.h")
	message(FATAL_ERROR "melonDS source not found in ${MELONDS_DIR}. Run any Gradle build (e.g. ./gradlew help): app/build.gradle.kts downloads it.")
endif()

# The emulator must be fast even in Debug builds, otherwise games run at a
# fraction of full speed. Keep debug info but always optimise.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

# libmgba is linked into a shared library, so it must be position independent.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# mGBA declares an old minimum CMake version; newer CMake needs this hint.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

# Build only libmgba: no Qt/SDL frontends, no optional dependencies.
set(M_CORE_GBA ON)
set(M_CORE_GB ON)
add_subdirectory("${MGBA_DIR}" mgba EXCLUDE_FROM_ALL)

# melonDS: only its core library (software 3D, no Qt, no OpenGL, no debugger).
# Its JIT recompiler is on where melonDS has one (ARM64 and x86_64). Phones
# with only 32-bit ARM get no DS support: without a JIT it would be far too
# slow, so the library isn't built for them at all.
if(ANDROID AND ANDROID_ABI STREQUAL "armeabi-v7a")
	set(WITH_NDS OFF)
else()
	set(WITH_NDS ON)
	set(BUILD_QT_SDL OFF CACHE BOOL "" FORCE)
	set(ENABLE_OGLRENDERER OFF CACHE BOOL "" FORCE)
	set(ENABLE_GDBSTUB OFF CACHE BOOL "" FORCE)
	set(ENABLE_LTO_RELEASE OFF CACHE BOOL "" FORCE)
	add_subdirectory("${MELONDS_DIR}" melonds EXCLUDE_FROM_ALL)
	set(NDS_SOURCES nds/nds_core.cpp nds/nds_platform.cpp nds/nds_states.cpp nds/nds_mic.cpp)
endif()

if(ANDROID)
	add_library(gbaemu SHARED emu_core.c jni_bridge.c)
	target_link_libraries(gbaemu PRIVATE mgba log)
	# Only the JNI entry points (JNIEXPORT) are visible outside the library.
	set_target_properties(gbaemu PROPERTIES C_VISIBILITY_PRESET hidden)
	# Only export JNI symbols; smaller library and faster loading.
	target_link_options(gbaemu PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
	# 16 KB page size support (required for new apps on Android 15+ devices).
	target_link_options(gbaemu PRIVATE "-Wl,-z,max-page-size=16384")

	if(WITH_NDS)
		add_library(ndsemu SHARED ${NDS_SOURCES} nds/jni_nds.cpp)
		target_link_libraries(ndsemu PRIVATE core log dl z)
		set_target_properties(ndsemu PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
		target_link_options(ndsemu PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
	endif()
else()
	# Desktop builds used by native-test/ to exercise the cores without a phone.
	add_library(gbaemu STATIC emu_core.c)
	target_link_libraries(gbaemu PUBLIC mgba)
	add_library(ndsemu STATIC ${NDS_SOURCES})
	target_link_libraries(ndsemu PUBLIC core dl pthread z)
endif()
if(WITH_NDS)
	target_include_directories(ndsemu PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/nds")
	target_compile_options(ndsemu PRIVATE -Wall -Wno-unused-parameter)
endif()

# 3DS: a small libretro frontend (n3ds/) for the Azahar core, which ships
# prebuilt for 64-bit ARM only and is loaded at run time.
set(N3DS_SOURCES n3ds/n3ds_rom.cpp n3ds/n3ds_vfs.cpp n3ds/n3ds_host.cpp n3ds/n3ds_gl.cpp)
if(ANDROID)
	if(ANDROID_ABI STREQUAL "arm64-v8a")
		add_library(n3ds SHARED ${N3DS_SOURCES} n3ds/n3ds_android.cpp)
		target_link_libraries(n3ds PRIVATE EGL GLESv3 android aaudio log dl z)
		set_target_properties(n3ds PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
		target_link_options(n3ds PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
		target_compile_options(n3ds PRIVATE -Wall -Wno-unused-parameter)
	endif()
else()
	# Desktop test (native-test/n3ds_host_test) needs EGL, e.g. Mesa's.
	find_library(EGL_LIBRARY EGL)
	if(EGL_LIBRARY)
		add_library(n3dshost STATIC ${N3DS_SOURCES})
		target_include_directories(n3dshost PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/n3ds")
		target_link_libraries(n3dshost PUBLIC ${EGL_LIBRARY} dl pthread)
		target_compile_options(n3dshost PRIVATE -Wall -Wno-unused-parameter)
	endif()
endif()

target_include_directories(gbaemu PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
# mGBA's headers expect the same feature macros its own sources get.
target_compile_definitions(gbaemu PRIVATE _GNU_SOURCE)
target_compile_options(gbaemu PRIVATE -Wall -Wextra -Wno-unused-parameter)
