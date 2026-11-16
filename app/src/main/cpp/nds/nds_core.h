/*
 * nds_core: small C API over melonDS, mirroring emu_core.h for the GBA side.
 *
 * Built into its own library (libndsemu.so) so melonDS and mGBA never share
 * symbols. Same threading rules as emu_core: call everything from the
 * emulator thread, except the input setters which only store values.
 */
#ifndef NDS_CORE_H
#define NDS_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NDS_SCREEN_WIDTH 256
#define NDS_SCREEN_HEIGHT 192

/* Key bits: the GBA ones (A, B, Select, Start, Right, Left, Up, Down, R, L)
 * plus X and Y. Active high. */
#define NDS_KEY_X (1 << 10)
#define NDS_KEY_Y (1 << 11)
/* Not a DS key: the JNI layer uses this bit for "blow into the mic". */
#define NDS_KEY_MIC (1 << 12)

/* Firmware languages, as stored in the DS user settings. */
enum NdsLanguage {
	NDS_LANG_JAPANESE = 0,
	NDS_LANG_ENGLISH = 1,
	NDS_LANG_FRENCH = 2,
	NDS_LANG_GERMAN = 3,
	NDS_LANG_ITALIAN = 4,
	NDS_LANG_SPANISH = 5,
};

/* The biggest DS cartridges are 512 MiB. */
#define NDS_MAX_ROM_SIZE ((size_t) 512 * 1024 * 1024)

/*
 * Loads a ROM image already in memory (it is copied).
 * savePath: battery save file, read if it exists and written as the game saves.
 * dataDir:  folder melonDS may use for its own small files.
 * useJit:   recompiler (much faster on ARM64; ignored where unavailable).
 */
bool nds_load(const void* rom, size_t romSize, const char* savePath, const char* dataDir,
              unsigned sampleRate, int language, bool useJit);
/* Same, reading the ROM from an open file descriptor (left open). Big DS
 * ROMs don't fit in Java's heap, so Android passes the file this way. */
bool nds_load_fd(int fd, const char* savePath, const char* dataDir, unsigned sampleRate, int language, bool useJit);
void nds_unload(void);
bool nds_is_loaded(void);
void nds_reset(void);
void nds_run_frame(void);

void nds_set_keys(uint32_t keys);
/* Touch on the bottom screen, in DS pixels (0-255, 0-191). */
void nds_touch(int x, int y, bool down);
/* Feed a "blowing into the mic" sound while true. */
void nds_set_mic_blow(bool blowing);

/* Copies both screens, top then bottom (256x384), as RGBA bytes with opaque
 * alpha. Returns bytes written. */
size_t nds_copy_video(void* dst, size_t dstCapacity, unsigned* width, unsigned* height);

size_t nds_read_audio(int16_t* dst, size_t maxFrames);
void nds_discard_audio(void);
/* Same meaning as emu_set_audio_speed: 4 = x4, 0.5 = half speed. */
void nds_set_audio_speed(float speed);

/* Writes the battery save to disk now if the game changed it. */
void nds_flush_save(void);

/* Game title from the ROM header (printable ASCII only). */
void nds_game_title(char* out, size_t outSize);

#ifdef __cplusplus
}
#endif

#endif
