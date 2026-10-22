/*
 * emu_core: thin, platform-agnostic wrapper around the mGBA core.
 *
 * Everything that talks to mGBA lives here so it can be compiled and tested
 * on a desktop machine (see native-test/) as well as on Android (jni_bridge.c).
 *
 * Threading: every function except the sensor/key setters must be called
 * from the same thread (the emulation thread). The setters only write plain
 * values that the core reads on the next frame.
 */
#ifndef EMU_CORE_H
#define EMU_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EMU_MAX_WIDTH 256
#define EMU_MAX_HEIGHT 224

enum EmuPlatform {
	EMU_PLATFORM_NONE = -1,
	EMU_PLATFORM_GBA = 0,
	EMU_PLATFORM_GB = 1,
};

/* Bit layout of the GBA KEYINPUT register (also used by mGBA for GB). */
enum EmuKey {
	EMU_KEY_A = 1 << 0,
	EMU_KEY_B = 1 << 1,
	EMU_KEY_SELECT = 1 << 2,
	EMU_KEY_START = 1 << 3,
	EMU_KEY_RIGHT = 1 << 4,
	EMU_KEY_LEFT = 1 << 5,
	EMU_KEY_UP = 1 << 6,
	EMU_KEY_DOWN = 1 << 7,
	EMU_KEY_R = 1 << 8,
	EMU_KEY_L = 1 << 9,
};

/* Cheat formats. EMU_CHEAT_AUTO lets mGBA guess the format per line. */
enum EmuCheatType {
	EMU_CHEAT_AUTO = 0,
	EMU_CHEAT_CODEBREAKER = 1,
	EMU_CHEAT_GAMESHARK = 2,
	EMU_CHEAT_ACTION_REPLAY = 3,
	EMU_CHEAT_RAW = 4, /* VBA style "AAAAAAAA:VV" */
	EMU_CHEAT_GAME_GENIE = 5, /* GB/GBC only */
};

/* Special cartridge hardware, so the app only turns on sensors when needed. */
enum EmuHardware {
	EMU_HW_RTC = 1,
	EMU_HW_RUMBLE = 2,
	EMU_HW_LIGHT_SENSOR = 4,
	EMU_HW_GYRO = 8,
	EMU_HW_TILT = 16,
};

struct EmuOptions {
	unsigned sampleRate; /* audio output rate, e.g. 48000 */
	bool skipBios;       /* only meaningful when a BIOS file is given */
	int frameskip;
	bool idleLoopDetect; /* speed hack, safe for the vast majority of games */
	bool gbColorize;     /* use GBC palettes for original Game Boy games */
};

/* Fills `opts` with sensible defaults. */
void emu_default_options(struct EmuOptions* opts);

/*
 * Loads a ROM image that is already in memory (the data is copied).
 * savePath:  battery save (.sav) file, created if missing. May be NULL.
 * biosPath:  optional BIOS image. NULL or "" uses the built-in HLE BIOS.
 */
bool emu_load(const void* rom, size_t romSize, const char* savePath, const char* biosPath, const struct EmuOptions* opts);
void emu_unload(void);
bool emu_is_loaded(void);
int emu_platform(void);

void emu_reset(void);
void emu_run_frame(void);

void emu_set_keys(uint32_t keys);

/* Current video size and pixel buffer (32-bit, bytes R,G,B,X in memory). */
void emu_video_size(unsigned* width, unsigned* height);
const uint32_t* emu_video_buffer(size_t* stridePixels);
/* Copies the current frame tightly packed into dst as R,G,B,A bytes with
 * opaque alpha (ready for GL_RGBA uploads and ARGB_8888 bitmaps).
 * Returns bytes written. */
size_t emu_copy_video(void* dst, size_t dstCapacity, unsigned* width, unsigned* height);

/* Reads up to maxFrames stereo frames (interleaved L/R int16). */
size_t emu_read_audio(int16_t* dst, size_t maxFrames);
void emu_discard_audio(void);
/* Plays audio `speed` times faster (fast forward): each emulated second
 * produces 1/speed seconds of audio, so pitch and tempo rise together. */
void emu_set_audio_speed(float speed);

bool emu_save_state(const char* path);
bool emu_load_state(const char* path);

/* Cheats: each call creates one named, independently toggleable cheat. */
void emu_cheats_clear(void);
bool emu_cheats_add(const char* name, const char* code, int type, bool enabled);

/* Rewind. capacity = number of snapshots, interval = frames between them. */
void emu_set_rewind(bool enabled, int capacity, int interval);
bool emu_rewind_step(void);

/* Sensors, in Android units: accelerometer m/s^2, gyroscope rad/s. */
void emu_set_tilt(float x, float y);
void emu_set_gyro(float z);
void emu_set_solar_level(int level); /* 0 (dark) .. 10 (full sun) */
/* Returns true if the game asked for rumble since the last call. */
bool emu_poll_rumble(void);

void emu_set_frameskip(int frameskip);

/* Bitmask of EmuHardware present in the loaded cartridge. */
int emu_hardware(void);

/* Internal title (12 chars max for GBA) and game code ("AGB-XXXX"). */
void emu_game_title(char* out, size_t outSize);
void emu_game_code(char* out, size_t outSize);

#ifdef __cplusplus
}
#endif

#endif
