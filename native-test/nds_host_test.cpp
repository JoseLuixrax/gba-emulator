// Desktop test for nds_core: boots the homebrew ROM built by nds-rom/build.py
// and checks video (both screens, colours, keys), audio rates at every
// speed and the battery save path. Exit code is non-zero on failure.
#include "nds_core.h"
#include "nds_session.h"
#include "Platform.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

static int failures = 0;

#define CHECK(cond, ...)                          \
	do {                                          \
		if (!(cond)) {                            \
			fprintf(stderr, "FAIL: " __VA_ARGS__); \
			fputc('\n', stderr);                  \
			++failures;                           \
		}                                         \
	} while (0)

static std::vector<uint8_t> readAll(const char* path) {
	std::vector<uint8_t> data;
	FILE* f = fopen(path, "rb");
	if (!f) return data;
	fseek(f, 0, SEEK_END);
	data.resize(ftell(f));
	fseek(f, 0, SEEK_SET);
	if (fread(data.data(), 1, data.size(), f) != data.size()) data.clear();
	fclose(f);
	return data;
}

static void writePpm(const char* path, const uint8_t* rgba, unsigned w, unsigned h) {
	FILE* f = fopen(path, "wb");
	if (!f) return;
	fprintf(f, "P6\n%u %u\n255\n", w, h);
	for (unsigned i = 0; i < w * h; ++i) fwrite(&rgba[i * 4], 1, 3, f);
	fclose(f);
}

static double runAndCountAudio(int frames) {
	static int16_t audio[16384 * 2];
	size_t total = 0;
	for (int i = 0; i < frames; ++i) {
		nds_run_frame();
		total += nds_read_audio(audio, 16384);
	}
	return total / (double) frames;
}

// Pixel (x, y) of the 256x384 two-screen image, as 0xRRGGBB.
static uint32_t pixel(const std::vector<uint8_t>& img, int x, int y) {
	const uint8_t* p = &img[(y * 256 + x) * 4];
	return (p[0] << 16) | (p[1] << 8) | p[2];
}

int main(int argc, char** argv) {
	if (argc < 3) {
		fprintf(stderr, "usage: %s <rom.nds> <output dir>\n", argv[0]);
		return 2;
	}
	const char* outDir = argv[2];
	mkdir(outDir, 0755);
	std::string savePath = std::string(outDir) + "/test.sav";
	remove(savePath.c_str());

	std::vector<uint8_t> rom = readAll(argv[1]);
	CHECK(!rom.empty(), "cannot read %s", argv[1]);
	CHECK(!nds_load("not a rom", 9, nullptr, outDir, 48000, NDS_LANG_SPANISH, true), "garbage accepted as a ROM");
	bool loaded = nds_load(rom.data(), rom.size(), savePath.c_str(), outDir, 48000, NDS_LANG_SPANISH, true);
	CHECK(loaded, "nds_load failed");
	if (!loaded) return 1;

	char title[32];
	nds_game_title(title, sizeof(title));
	CHECK(strcmp(title, "ADVBOYTEST") == 0, "title is '%s'", title);

	double perFrame = runAndCountAudio(120);
	CHECK(perFrame > 780 && perFrame < 820, "audio %.1f samples/frame at 1x", perFrame);

	std::vector<uint8_t> img(256 * 384 * 4);
	unsigned w = 0, h = 0;
	CHECK(nds_copy_video(img.data(), img.size(), &w, &h) == img.size() && w == 256 && h == 384, "video size %ux%u", w, h);
	writePpm((std::string(outDir) + "/frame.ppm").c_str(), img.data(), w, h);
	CHECK(pixel(img, 128, 40) == 0x0000FF || (pixel(img, 128, 40) & 0xF8F8F8) == 0x0000F8, "top half should be blue, is %06X", pixel(img, 128, 40));
	CHECK((pixel(img, 128, 150) & 0xF8F8F8) == 0x00F800, "lower top-screen half should be green, is %06X", pixel(img, 128, 150));
	CHECK(img[3] == 0xFF, "alpha should be opaque");

	// Holding A turns the upper half red.
	nds_set_keys(1);
	runAndCountAudio(3);
	nds_copy_video(img.data(), img.size(), &w, &h);
	CHECK((pixel(img, 128, 40) & 0xF8F8F8) == 0xF80000, "with A held the top should be red, is %06X", pixel(img, 128, 40));
	nds_set_keys(0);

	nds_touch(300, -5, true);  // clamped, must not crash
	runAndCountAudio(2);
	nds_touch(0, 0, false);
	nds_set_mic_blow(true);
	runAndCountAudio(2);
	nds_set_mic_blow(false);

	nds_set_audio_speed(4.0f);
	double ff = runAndCountAudio(60);
	CHECK(ff > perFrame / 4 - 10 && ff < perFrame / 4 + 10, "4x audio %.1f samples/frame", ff);
	nds_set_audio_speed(0.1f);
	double slow = runAndCountAudio(20);
	CHECK(slow > perFrame * 10 - 60 && slow < perFrame * 10 + 60, "0.1x audio %.1f samples/frame", slow);
	nds_set_audio_speed(1.0f);
	nds_discard_audio();

	// Battery saves: what the game writes reaches the disk once it settles.
	uint8_t fake[512];
	for (int i = 0; i < 512; ++i) fake[i] = (uint8_t) i;
	melonDS::Platform::WriteNDSSave(fake, sizeof(fake), 0, sizeof(fake), gNdsSession);
	runAndCountAudio(5);
	CHECK(readAll(savePath.c_str()).empty(), "save written before it settled");
	runAndCountAudio(40);
	std::vector<uint8_t> saved = readAll(savePath.c_str());
	CHECK(saved.size() == 512 && memcmp(saved.data(), fake, 512) == 0, "save file has %zu bytes", saved.size());

	nds_reset();
	runAndCountAudio(10);
	nds_unload();
	CHECK(!nds_is_loaded(), "still loaded after unload");

	// The Android app passes big ROMs as a file descriptor: a real file...
	int fd = open(argv[1], O_RDONLY);
	CHECK(fd >= 0 && nds_load_fd(fd, savePath.c_str(), outDir, 48000, NDS_LANG_ENGLISH, true), "nds_load_fd (file) failed");
	if (fd >= 0) close(fd);
	nds_game_title(title, sizeof(title));
	CHECK(strcmp(title, "ADVBOYTEST") == 0, "title after fd load is '%s'", title);
	runAndCountAudio(5);
	CHECK(readAll(savePath.c_str()).size() == 512, "save changed by a plain reload");

	// ...or a pipe, where the size isn't known in advance.
	int fds[2];
	CHECK(pipe(fds) == 0, "pipe");
	std::thread writer([&] {
		size_t done = 0;
		while (done < rom.size()) {
			ssize_t n = write(fds[1], rom.data() + done, rom.size() - done);
			if (n <= 0) break;
			done += (size_t) n;
		}
		close(fds[1]);
	});
	bool piped = nds_load_fd(fds[0], nullptr, outDir, 48000, NDS_LANG_ENGLISH, false);
	writer.join();
	close(fds[0]);
	CHECK(piped, "nds_load_fd (pipe) failed");
	runAndCountAudio(5);
	nds_unload();

	if (failures) {
		fprintf(stderr, "%d check(s) failed\n", failures);
		return 1;
	}
	printf("DS core OK: %.1f samples/frame (4x %.1f, 0.1x %.1f), title %s\n", perFrame, ff, slow, title);
	return 0;
}
