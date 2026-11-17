// Desktop test for nds_core: boots the homebrew ROM built by nds-rom/build.py
// and checks video (both screens, colours, keys), audio rates at every
// speed, the battery save path, save states, rewind and Action Replay
// cheats. Exit code is non-zero on failure.
#include "nds_core.h"
#include "nds_session.h"
#include "Platform.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <zlib.h>

#include <algorithm>
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

// The test ROM counts frames in RAM and draws the count as 12 bits in the
// first pixels of the top screen (red = 1).
static int frameCounter() {
	std::vector<uint8_t> img(256 * 384 * 4);
	unsigned w = 0, h = 0;
	nds_copy_video(img.data(), img.size(), &w, &h);
	int count = 0;
	for (int bit = 0; bit < 12; ++bit) {
		if ((pixel(img, bit, 0) >> 16) > 0x80) count |= 1 << bit;
	}
	return count;
}

static void writeAll(const std::string& path, const void* data, size_t size) {
	FILE* f = fopen(path.c_str(), "wb");
	if (!f) return;
	fwrite(data, 1, size, f);
	fclose(f);
}

// The ARM7 of the test ROM keeps reading the microphone through the
// touchscreen controller and leaves the result (0-127, 64 = silence) here.
static int micReading() {
	return gNdsSession->nds->MainRAM[0x100004];
}

static void testMicrophone() {
	runAndCountAudio(3);
	CHECK(nds_mic_wanted(), "the game reads the mic but it isn't marked as listening");
	int quiet = micReading();
	CHECK(quiet >= 62 && quiet <= 66, "with no mic the reading is %d, should be ~64", quiet);

	// The blow button: noise, so the reading moves well away from silence.
	nds_set_mic_blow(true);
	int farthest = 0;
	for (int i = 0; i < 20; ++i) {
		runAndCountAudio(1);
		farthest = std::max(farthest, abs(micReading() - 64));
	}
	nds_set_mic_blow(false);
	CHECK(farthest > 10, "blowing never moved the mic reading (at most %d from silence)", farthest);

	// The phone's mic: what is pushed is what the game reads.
	nds_set_mic_live(true);
	std::vector<int16_t> loud(48000 / 60 * 2, 0x6000);
	for (int i = 0; i < 4; ++i) {
		nds_push_mic(loud.data(), loud.size());
		runAndCountAudio(1);
	}
	int high = micReading();
	CHECK(high >= 108 && high <= 116, "a loud positive signal reads %d, should be ~112", high);
	std::vector<int16_t> negative(loud.size(), -0x6000);
	for (int i = 0; i < 4; ++i) {
		nds_push_mic(negative.data(), negative.size());
		runAndCountAudio(1);
	}
	int low = micReading();
	CHECK(low >= 12 && low <= 20, "a loud negative signal reads %d, should be ~16", low);
	printf("mic: silence %d, blowing up to %d away, loud %d, negative %d\n", quiet, farthest, high, low);

	// The buffer stays small however much is pushed, and empties when the
	// app stops recording; pushes after that are ignored.
	std::vector<int16_t> lots(100000, 0);
	nds_push_mic(lots.data(), lots.size());
	CHECK(nds_mic_buffered() <= 4096, "%zu samples waiting in the mic buffer", nds_mic_buffered());
	nds_set_mic_live(false);
	CHECK(nds_mic_buffered() == 0, "mic buffer not emptied");
	nds_push_mic(loud.data(), loud.size());
	CHECK(nds_mic_buffered() == 0, "samples accepted while not recording");
	runAndCountAudio(3);
	quiet = micReading();
	CHECK(quiet >= 62 && quiet <= 66, "after recording stops the reading is %d, should be ~64", quiet);
}

static void testStatesRewindCheats(const std::string& outDir) {
	// The counter moves one step per frame.
	int c0 = frameCounter();
	runAndCountAudio(10);
	int c1 = frameCounter();
	CHECK(c1 - c0 == 10, "frame counter went %d -> %d in 10 frames", c0, c1);

	// Save states: back to the saved moment, plus the one frame we run.
	std::string statePath = outDir + "/slot1.state";
	remove(statePath.c_str());
	CHECK(nds_save_state(statePath.c_str()), "nds_save_state failed");
	int onSave = frameCounter();
	runAndCountAudio(30);
	CHECK(nds_load_state(statePath.c_str()), "nds_load_state failed");
	runAndCountAudio(1);
	int afterLoad = frameCounter();
	CHECK(afterLoad - onSave >= 0 && afterLoad - onSave <= 2, "after loading the counter is %d, saved at %d", afterLoad, onSave);

	std::vector<uint8_t> packed = readAll(statePath.c_str());
	uint32_t rawLength = 0;
	if (packed.size() > 12) memcpy(&rawLength, &packed[8], 4);
	CHECK(packed.size() > 12 && memcmp(packed.data(), "ABDSST01", 8) == 0, "state file header");
	CHECK(rawLength > 1024 * 1024 && packed.size() < rawLength / 2, "state %u bytes, file %zu bytes", rawLength, packed.size());

	// A plain melonDS state (.mln) loads too.
	std::vector<uint8_t> raw(rawLength);
	uLongf rawSize = rawLength;
	bool unpacked = packed.size() > 12 && uncompress(raw.data(), &rawSize, packed.data() + 12, packed.size() - 12) == Z_OK;
	CHECK(unpacked && rawSize == rawLength && memcmp(raw.data(), "MELN", 4) == 0, "state doesn't unpack to a melonDS state");
	std::string mlnPath = outDir + "/plain.mln";
	writeAll(mlnPath, raw.data(), raw.size());
	runAndCountAudio(20);
	CHECK(nds_load_state(mlnPath.c_str()), "loading a .mln failed");
	runAndCountAudio(1);
	int afterMln = frameCounter();
	CHECK(afterMln - onSave >= 0 && afterMln - onSave <= 2, "after the .mln the counter is %d, saved at %d", afterMln, onSave);

	// Bad files are refused and the game goes on where it was.
	std::string junkPath = outDir + "/junk.state";
	writeAll(junkPath, "ABDSST01\x10\x00\x00\x00garbage", 19);
	int beforeJunk = frameCounter();
	CHECK(!nds_load_state(junkPath.c_str()), "a damaged state was accepted");
	CHECK(!nds_load_state((outDir + "/missing.state").c_str()), "a missing state was accepted");
	runAndCountAudio(1);
	CHECK(frameCounter() == beforeJunk + 1, "the game didn't go on after a refused state (%d -> %d)", beforeJunk, frameCounter());

	// Rewind: a snapshot every 3 frames; each step goes back one of them.
	CHECK(!nds_rewind_step(), "rewind worked while disabled");
	nds_set_rewind(true, 100, 3);
	runAndCountAudio(60);
	int beforeRewind = frameCounter();
	int previous = beforeRewind;
	bool decreasing = true;
	for (int i = 0; i < 5; ++i) {
		CHECK(nds_rewind_step(), "rewind step %d failed", i);
		int c = frameCounter();
		if (c >= previous) decreasing = false;
		previous = c;
	}
	CHECK(decreasing, "rewinding didn't go back in time");
	int back = beforeRewind - previous;
	CHECK(back >= 12 && back <= 18, "5 rewind steps went back %d frames", back);
	size_t steps = gNdsSession->rewindSteps.size();
	size_t bytes = gNdsSession->rewindBytes;
	printf("rewind: %zu steps in memory, %zu bytes in total\n", steps, bytes);
	// Playing again continues from there, and rewinding still works.
	runAndCountAudio(30);
	CHECK(nds_rewind_step(), "rewind after resuming failed");
	int stepsTaken = 1;
	while (nds_rewind_step()) ++stepsTaken;
	CHECK(stepsTaken >= 10 && stepsTaken <= 100, "%d rewind steps until the start of the history", stepsTaken);
	// The capacity is respected.
	nds_set_rewind(true, 5, 1);
	runAndCountAudio(30);
	CHECK(gNdsSession->rewindSteps.size() == 5, "capacity 5 kept %zu steps", gNdsSession->rewindSteps.size());
	nds_set_rewind(false, 0, 0);
	CHECK(!nds_rewind_step(), "rewind worked after turning it off");
	CHECK(gNdsSession->rewindNewest.empty(), "rewind memory not freed");

	// Action Replay: "0XXXXXXX YYYYYYYY" writes YYYYYYYY to XXXXXXX, here
	// the frame counter, every VBlank.
	CHECK(!nds_cheats_add("bad", "XYZ12345 00000001", true), "accepted non-hex code");
	CHECK(!nds_cheats_add("odd", "02100000", true), "accepted a half code");
	CHECK(!nds_cheats_add("short", "2100000 00000100", true), "accepted a 7-digit word");
	CHECK(nds_cheats_add("off", "0210000000000800", false), "16-digit form refused");
	CHECK(nds_cheats_add("counter", "02100000 00000100\n", true), "valid code refused");
	runAndCountAudio(5);
	int cheated = frameCounter();
	CHECK(cheated >= 0x100 && cheated <= 0x102, "with the cheat the counter is %d", cheated);
	nds_cheats_clear();
	runAndCountAudio(5);
	int freed = frameCounter();
	// The picture runs a frame or two behind the counter; what matters is
	// that it counts again.
	CHECK(freed >= cheated + 2 && freed <= cheated + 6, "after clearing cheats the counter is %d (was %d)", freed, cheated);
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
	CHECK(!nds_load("not a rom", 9, nullptr, outDir, 48000, NDS_LANG_SPANISH, nullptr, true), "garbage accepted as a ROM");
	bool loaded = nds_load(rom.data(), rom.size(), savePath.c_str(), outDir, 48000, NDS_LANG_SPANISH, "José", true);
	CHECK(loaded, "nds_load failed");
	if (!loaded) return 1;

	// Firmware: language and the player's name (UTF-8 in, UTF-16 in the firmware).
	const auto& user = gNdsSession->nds->GetFirmware().GetEffectiveUserData();
	CHECK((user.Settings & 7) == NDS_LANG_SPANISH, "firmware language is %d", user.Settings & 7);
	CHECK(user.NameLength == 4 && user.Nickname[0] == u'J' && user.Nickname[3] == u'\u00e9',
	      "firmware nickname has %d characters", user.NameLength);

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

	// The lid: closed, still closed after a reset, open again.
	CHECK(!nds_lid_closed(), "lid closed from the start");
	nds_set_lid(true);
	runAndCountAudio(2);
	CHECK(nds_lid_closed(), "lid didn't close");
	nds_reset();
	runAndCountAudio(1);
	CHECK(nds_lid_closed(), "lid opened by a reset while it should stay closed");
	nds_set_lid(false);
	runAndCountAudio(2);
	CHECK(!nds_lid_closed(), "lid didn't open");

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

	testMicrophone();
	testStatesRewindCheats(outDir);

	nds_reset();
	runAndCountAudio(10);
	nds_unload();
	CHECK(!nds_is_loaded(), "still loaded after unload");

	// The Android app passes big ROMs as a file descriptor: a real file...
	int fd = open(argv[1], O_RDONLY);
	CHECK(fd >= 0 && nds_load_fd(fd, savePath.c_str(), outDir, 48000, NDS_LANG_ENGLISH, "", true), "nds_load_fd (file) failed");
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
	bool piped = nds_load_fd(fds[0], nullptr, outDir, 48000, NDS_LANG_ENGLISH, nullptr, false);
	CHECK(!piped || gNdsSession->nds->GetFirmware().GetEffectiveUserData().NameLength == 7,
	      "without a nickname the default (melonDS) should stay");
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
