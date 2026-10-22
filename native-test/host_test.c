/*
 * Desktop test harness for emu_core.
 *
 * Runs every .gba in <rom dir> for a few seconds of emulated time, writes a
 * PNG screenshot per ROM and checks save states, battery saves, rewind,
 * cheats and audio output. Exit code is non-zero if a check fails.
 */
#include "emu_core.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static int failures = 0;

#define CHECK(cond, ...)                          \
	do {                                          \
		if (!(cond)) {                            \
			fprintf(stderr, "FAIL: " __VA_ARGS__); \
			fputc('\n', stderr);                  \
			++failures;                           \
		}                                         \
	} while (0)

/* ------------------------------------------------- minimal PNG writer */

static uint32_t crcTable[256];

static void crcInit(void) {
	for (uint32_t n = 0; n < 256; ++n) {
		uint32_t c = n;
		for (int k = 0; k < 8; ++k) {
			c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
		}
		crcTable[n] = c;
	}
}

static uint32_t crc(const uint8_t* buf, size_t len, uint32_t c) {
	c ^= 0xFFFFFFFFu;
	for (size_t i = 0; i < len; ++i) {
		c = crcTable[(c ^ buf[i]) & 0xFF] ^ (c >> 8);
	}
	return c ^ 0xFFFFFFFFu;
}

static void put32(uint8_t* p, uint32_t v) {
	p[0] = v >> 24;
	p[1] = v >> 16;
	p[2] = v >> 8;
	p[3] = v;
}

static void writeChunk(FILE* f, const char* type, const uint8_t* data, uint32_t len) {
	uint8_t hdr[8];
	put32(hdr, len);
	memcpy(hdr + 4, type, 4);
	fwrite(hdr, 1, 8, f);
	if (len) {
		fwrite(data, 1, len, f);
	}
	uint32_t c = crc((const uint8_t*) type, 4, 0);
	c = crc(data, len, c);
	uint8_t tail[4];
	put32(tail, c);
	fwrite(tail, 1, 4, f);
}

/* Writes RGBX pixels as an RGB PNG using stored (uncompressed) deflate blocks. */
static void writePng(const char* path, const uint8_t* rgbx, unsigned w, unsigned h) {
	size_t rawLen = (size_t) h * (1 + w * 3);
	uint8_t* raw = malloc(rawLen);
	for (unsigned y = 0; y < h; ++y) {
		uint8_t* row = raw + y * (1 + w * 3);
		row[0] = 0;
		for (unsigned x = 0; x < w; ++x) {
			memcpy(&row[1 + x * 3], &rgbx[(y * w + x) * 4], 3);
		}
	}
	size_t blocks = (rawLen + 65534) / 65535;
	size_t zLen = 2 + rawLen + blocks * 5 + 4;
	uint8_t* z = malloc(zLen);
	size_t o = 0;
	z[o++] = 0x78;
	z[o++] = 0x01;
	uint32_t a = 1, b = 0;
	for (size_t i = 0; i < rawLen; ++i) {
		a = (a + raw[i]) % 65521;
		b = (b + a) % 65521;
	}
	for (size_t off = 0; off < rawLen; off += 65535) {
		size_t n = rawLen - off > 65535 ? 65535 : rawLen - off;
		z[o++] = off + n >= rawLen ? 1 : 0;
		z[o++] = n & 0xFF;
		z[o++] = n >> 8;
		z[o++] = ~n & 0xFF;
		z[o++] = (~n >> 8) & 0xFF;
		memcpy(z + o, raw + off, n);
		o += n;
	}
	put32(z + o, (b << 16) | a);
	o += 4;

	FILE* f = fopen(path, "wb");
	if (!f) {
		fprintf(stderr, "cannot write %s\n", path);
		free(raw);
		free(z);
		return;
	}
	static const uint8_t sig[8] = { 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
	fwrite(sig, 1, 8, f);
	uint8_t ihdr[13];
	put32(ihdr, w);
	put32(ihdr + 4, h);
	ihdr[8] = 8;  /* bit depth */
	ihdr[9] = 2;  /* RGB */
	ihdr[10] = 0;
	ihdr[11] = 0;
	ihdr[12] = 0;
	writeChunk(f, "IHDR", ihdr, 13);
	writeChunk(f, "IDAT", z, (uint32_t) o);
	writeChunk(f, "IEND", NULL, 0);
	fclose(f);
	free(raw);
	free(z);
}

/* ---------------------------------------------------------- helpers */

static uint8_t* readFile(const char* path, size_t* size) {
	FILE* f = fopen(path, "rb");
	if (!f) {
		return NULL;
	}
	fseek(f, 0, SEEK_END);
	long len = ftell(f);
	fseek(f, 0, SEEK_SET);
	uint8_t* data = malloc(len);
	if (fread(data, 1, len, f) != (size_t) len) {
		free(data);
		fclose(f);
		return NULL;
	}
	fclose(f);
	*size = len;
	return data;
}

static long fileSize(const char* path) {
	struct stat st;
	if (stat(path, &st) != 0) {
		return -1;
	}
	return st.st_size;
}

static uint8_t frameA[EMU_MAX_WIDTH * EMU_MAX_HEIGHT * 4];
static uint8_t frameB[EMU_MAX_WIDTH * EMU_MAX_HEIGHT * 4];

static size_t snapshot(uint8_t* dst, unsigned* w, unsigned* h) {
	return emu_copy_video(dst, sizeof(frameA), w, h);
}

static void runFrames(int n, size_t* audioFrames) {
	static int16_t audio[8192 * 2];
	for (int i = 0; i < n; ++i) {
		emu_run_frame();
		size_t got = emu_read_audio(audio, 8192);
		if (audioFrames) {
			*audioFrames += got;
		}
	}
}

/* ------------------------------------------------------------ tests */

static void testRom(const char* romDir, const char* name, const char* outDir) {
	char path[1024], savePath[1024], statePath[1024], pngPath[1024];
	snprintf(path, sizeof(path), "%s/%s", romDir, name);
	snprintf(savePath, sizeof(savePath), "%s/%s.sav", outDir, name);
	snprintf(statePath, sizeof(statePath), "%s/%s.ss1", outDir, name);
	snprintf(pngPath, sizeof(pngPath), "%s/%s.png", outDir, name);
	remove(savePath);

	size_t romSize = 0;
	uint8_t* rom = readFile(path, &romSize);
	CHECK(rom, "%s: cannot read ROM", name);
	if (!rom) {
		return;
	}

	struct EmuOptions opts;
	emu_default_options(&opts);
	if (getenv("EMU_NO_IDLE")) {
		opts.idleLoopDetect = false;
	}
	bool loaded = emu_load(rom, romSize, savePath, NULL, &opts);
	free(rom);
	CHECK(loaded, "%s: emu_load failed", name);
	if (!loaded) {
		return;
	}
	bool isGba = strstr(name, ".gba") != NULL;
	CHECK(emu_platform() == (isGba ? EMU_PLATFORM_GBA : EMU_PLATFORM_GB), "%s: wrong platform %d", name, emu_platform());

	char title[32], code[32];
	emu_game_title(title, sizeof(title));
	emu_game_code(code, sizeof(code));

	/* ~3 seconds of emulated time by default. */
	int frames = getenv("EMU_FRAMES") ? atoi(getenv("EMU_FRAMES")) : 180;
	size_t audioFrames = 0;
	runFrames(frames, &audioFrames);
	double perFrame = audioFrames / (double) frames;
	CHECK(perFrame > 780 && perFrame < 830, "%s: unexpected audio rate %.1f samples/frame", name, perFrame);

	unsigned w, h;
	size_t bytes = snapshot(frameA, &w, &h);
	unsigned expectW = isGba ? 240 : 160, expectH = isGba ? 160 : 144;
	CHECK(bytes == expectW * expectH * 4 && w == expectW && h == expectH, "%s: bad video size %ux%u", name, w, h);
	writePng(pngPath, frameA, w, h);

	if (getenv("EMU_BASIC_ONLY")) {
		emu_unload();
		printf("%-14s basic sav=%ld\n", name, fileSize(savePath));
		return;
	}

	/* Save state determinism: state -> run 60 -> load -> run 60 -> same frame. */
	CHECK(emu_save_state(statePath), "%s: save state failed", name);
	runFrames(60, NULL);
	snapshot(frameA, &w, &h);
	CHECK(emu_load_state(statePath), "%s: load state failed", name);
	runFrames(60, NULL);
	snapshot(frameB, &w, &h);
	CHECK(memcmp(frameA, frameB, w * h * 4) == 0, "%s: state reload is not deterministic", name);

	/* Fast forward with sound at 4x: a quarter of the samples per frame. */
	emu_set_audio_speed(4.0f);
	emu_discard_audio();
	size_t ffAudio = 0;
	runFrames(120, &ffAudio);
	emu_set_audio_speed(1.0f);
	double ffPerFrame = ffAudio / 120.0;
	CHECK(ffPerFrame > perFrame / 4 - 10 && ffPerFrame < perFrame / 4 + 10, "%s: 4x audio gives %.1f samples/frame", name, ffPerFrame);

	/* Slow motion x0.1: ten times the samples, all of them kept. */
	emu_set_audio_speed(0.1f);
	emu_discard_audio();
	size_t slowAudio = 0;
	runFrames(30, &slowAudio);
	emu_set_audio_speed(1.0f);
	double slowPerFrame = slowAudio / 30.0;
	CHECK(slowPerFrame > perFrame * 10 - 40 && slowPerFrame < perFrame * 10 + 40, "%s: 0.1x audio gives %.1f samples/frame", name, slowPerFrame);

	/* Fast forward path: frames whose audio is thrown away. */
	for (int i = 0; i < 30; ++i) {
		emu_run_frame();
		emu_discard_audio();
	}

	/* Rewind: record, step back, keep running. */
	emu_set_rewind(true, 120, 1);
	runFrames(90, NULL);
	int steps = 0;
	for (int i = 0; i < 60; ++i) {
		steps += emu_rewind_step();
	}
	CHECK(steps == 60, "%s: rewind stepped %d/60", name, steps);
	runFrames(30, NULL);
	emu_set_rewind(false, 0, 0);

	/* Cheats: a raw VBA-style write must be accepted, garbage rejected. */
	CHECK(emu_cheats_add("raw", isGba ? "03000000:12" : "C000:12", 4, true), "%s: raw cheat rejected", name);
	CHECK(!emu_cheats_add("junk", "not a cheat", 0, true), "%s: junk cheat accepted", name);
	runFrames(5, NULL);
	emu_cheats_clear();
	runFrames(5, NULL);

	emu_unload();
	printf("%-14s title=%-12s code=%-8s audio=%.1f/frame sav=%ld bytes -> %s\n", name, title, code, perFrame,
	       fileSize(savePath), pngPath);
}

int main(int argc, char** argv) {
	if (argc < 3) {
		fprintf(stderr, "usage: %s <rom dir> <output dir>\n", argv[0]);
		return 2;
	}
	crcInit();
	const char* romDir = argv[1];
	const char* outDir = argv[2];
	mkdir(outDir, 0755);

	DIR* dir = opendir(romDir);
	if (!dir) {
		fprintf(stderr, "cannot open %s\n", romDir);
		return 2;
	}
	struct dirent* entry;
	int count = 0;
	while ((entry = readdir(dir))) {
		const char* ext = strrchr(entry->d_name, '.');
		if (ext && (!strcmp(ext, ".gba") || !strcmp(ext, ".gb") || !strcmp(ext, ".gbc"))) {
			testRom(romDir, entry->d_name, outDir);
			++count;
		}
	}
	closedir(dir);
	CHECK(count > 0, "no ROMs found in %s", romDir);

	/* Battery saves must survive unload: the save test ROMs write on boot. */
	if (getenv("EMU_SKIP_SAVE_CHECK")) {
		return failures ? 1 : 0;
	}
	char savePath[1024];
	snprintf(savePath, sizeof(savePath), "%s/flash128.gba.sav", outDir);
	CHECK(fileSize(savePath) == 128 * 1024, "flash128 save has size %ld", fileSize(savePath));
	snprintf(savePath, sizeof(savePath), "%s/sram.gba.sav", outDir);
	CHECK(fileSize(savePath) == 32 * 1024, "sram save has size %ld", fileSize(savePath));

	if (failures) {
		fprintf(stderr, "%d check(s) failed\n", failures);
		return 1;
	}
	printf("All %d ROMs passed\n", count);
	return 0;
}
