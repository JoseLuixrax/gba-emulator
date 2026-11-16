#include "nds_core.h"

#include "nds_session.h"

#include "Args.h"
#include "GPU3D_Soft.h"
#include "NDS.h"
#include "NDSCart.h"
#include "Platform.h"
#include "SPI_Firmware.h"

#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <new>
#include <vector>

using namespace melonDS;

NdsSession* gNdsSession = nullptr;

/* The game must stop writing its save for this long before it goes to disk:
 * saves arrive in many small chunks and a half-written file is useless. */
static const int SAVE_SETTLE_FRAMES = 30;

static void logMessage(const char* message) {
	Platform::Log(Platform::LogLevel::Warn, "%s\n", message);
}

static bool readFile(const std::string& path, std::unique_ptr<u8[]>& data, u32& length) {
	FILE* file = fopen(path.c_str(), "rb");
	if (!file) {
		return false;
	}
	fseek(file, 0, SEEK_END);
	long size = ftell(file);
	fseek(file, 0, SEEK_SET);
	if (size <= 0) {
		fclose(file);
		return false;
	}
	data = std::make_unique<u8[]>(size);
	bool ok = fread(data.get(), 1, size, file) == (size_t) size;
	fclose(file);
	length = ok ? (u32) size : 0;
	return ok;
}

static void writeSaveToDisk(NdsSession* session) {
	std::vector<u8> copy;
	{
		std::lock_guard<std::mutex> guard(session->saveLock);
		if (!session->saveDirty || session->savePath.empty()) {
			return;
		}
		copy = session->saveData;
		session->saveDirty = false;
	}
	std::string tmp = session->savePath + ".tmp";
	FILE* file = fopen(tmp.c_str(), "wb");
	if (!file) {
		logMessage("Cannot write the DS save file");
		return;
	}
	bool ok = fwrite(copy.data(), 1, copy.size(), file) == copy.size();
	ok = fflush(file) == 0 && ok;
	fclose(file);
	if (!ok || rename(tmp.c_str(), session->savePath.c_str()) != 0) {
		remove(tmp.c_str());
		logMessage("Saving the DS save file failed");
	}
}

static u32 readU32(const u8* p) {
	return p[0] | (p[1] << 8) | (p[2] << 16) | ((u32) p[3] << 24);
}

/* melonDS trusts the header, so a random file can crash it. Check that the
 * ARM9 and ARM7 programs the header points to are really inside the file. */
static bool looksLikeNdsRom(const u8* rom, size_t size) {
	if (size < 0x200) {
		return false;
	}
	u32 arm9Offset = readU32(rom + 0x20), arm9Size = readU32(rom + 0x2C);
	u32 arm7Offset = readU32(rom + 0x30), arm7Size = readU32(rom + 0x3C);
	auto inside = [size](u32 offset, u32 length) {
		return offset >= 0x200 && length > 0 && (u64) offset + length <= size;
	};
	return inside(arm9Offset, arm9Size) && inside(arm7Offset, arm7Size);
}

static void setFirmwareLanguage(Firmware& firmware, int language) {
	if (language < 0 || language > NDS_LANG_SPANISH) {
		return;
	}
	for (auto& userData : firmware.GetUserData()) {
		userData.Settings &= ~Firmware::Language::Reserved;
		userData.Settings |= language;
	}
	firmware.UpdateChecksums();
}

static void setClockToNow(NDS& nds) {
	time_t now = time(nullptr);
	struct tm local;
	localtime_r(&now, &local);
	nds.RTC.SetDateTime(local.tm_year + 1900, local.tm_mon + 1, local.tm_mday, local.tm_hour, local.tm_min, local.tm_sec);
}

/* Reads everything from fd (a file or a pipe) without closing it. */
static bool readAll(int fd, std::unique_ptr<u8[]>& data, size_t& length) {
	struct stat st;
	size_t capacity = 0;
	if (fstat(fd, &st) == 0 && st.st_size > 0) {
		if ((u64) st.st_size > NDS_MAX_ROM_SIZE) {
			logMessage("The file is too large to be a DS ROM");
			return false;
		}
		capacity = (size_t) st.st_size;
	}
	std::vector<u8> grown;
	if (capacity) {
		data.reset(new (std::nothrow) u8[capacity]);
		if (!data) {
			logMessage("Not enough memory for the DS ROM");
			return false;
		}
	}
	length = 0;
	u8 chunk[64 * 1024];
	while (true) {
		u8* dst = capacity ? data.get() + length : chunk;
		size_t room = capacity ? capacity - length : sizeof(chunk);
		if (room == 0) {
			break;
		}
		ssize_t n = read(fd, dst, room);
		if (n < 0 && errno == EINTR) {
			continue;
		}
		if (n < 0) {
			logMessage("Reading the DS ROM failed");
			return false;
		}
		if (n == 0) {
			break;
		}
		if (!capacity) {
			if (grown.size() + n > NDS_MAX_ROM_SIZE) {
				logMessage("The file is too large to be a DS ROM");
				return false;
			}
			grown.insert(grown.end(), chunk, chunk + n);
		}
		length += (size_t) n;
	}
	if (!capacity) {
		data.reset(new (std::nothrow) u8[grown.size() ? grown.size() : 1]);
		if (!data) {
			return false;
		}
		memcpy(data.get(), grown.data(), grown.size());
	}
	return length > 0;
}

static bool loadOwned(std::unique_ptr<u8[]> rom, size_t romSize, const char* savePath, const char* dataDir,
                      unsigned sampleRate, int language, bool useJit) {
	nds_unload();
	if (!rom || !looksLikeNdsRom(rom.get(), romSize)) {
		logMessage("Not a DS ROM, or a damaged one");
		return false;
	}

	NdsSession* session = new NdsSession();
	session->savePath = savePath ? savePath : "";
	session->dataDir = dataDir ? dataDir : ".";
	session->sampleRate = sampleRate ? sampleRate : 48000;
	gNdsSession = session;

	// FreeBIOS and generated firmware: no files from a real console needed.
	NDSArgs args;
	args.OutputSampleRate = session->sampleRate;
#ifdef JIT_ENABLED
	if (!useJit) {
		args.JIT = std::nullopt;
	}
#else
	(void) useJit;
#endif
	setFirmwareLanguage(args.Firmware, language);

	session->nds = std::make_unique<NDS>(std::move(args), session);
	NDS& nds = *session->nds;

	// Software 3D on its own thread: frees the emulator thread on multi-core phones.
	auto renderer = std::make_unique<SoftRenderer>();
	renderer->SetThreaded(true, nds.GPU);
	nds.GPU.SetRenderer3D(std::move(renderer));
	nds.Reset();

	std::unique_ptr<u8[]> save;
	u32 saveLength = 0;
	if (!session->savePath.empty() && readFile(session->savePath, save, saveLength)) {
		std::lock_guard<std::mutex> guard(session->saveLock);
		session->saveData.assign(save.get(), save.get() + saveLength);
	}

	NDSCart::NDSCartArgs cartArgs;
	cartArgs.SRAM = std::move(save);
	cartArgs.SRAMLength = saveLength;
	auto cart = NDSCart::ParseROM(std::move(rom), (u32) romSize, session, std::move(cartArgs));
	if (!cart) {
		logMessage("Not a DS ROM, or a damaged one");
		nds_unload();
		return false;
	}

	nds.SetNDSCart(std::move(cart));
	nds.Reset();
	// Without the original BIOS/firmware the game is started directly.
	if (nds.NeedsDirectBoot()) {
		nds.SetupDirectBoot("game.nds");
	}
	setClockToNow(nds);
	nds.Start();
	return true;
}

extern "C" {

bool nds_load(const void* rom, size_t romSize, const char* savePath, const char* dataDir,
              unsigned sampleRate, int language, bool useJit) {
	if (!rom || romSize == 0 || romSize > NDS_MAX_ROM_SIZE) {
		return false;
	}
	std::unique_ptr<u8[]> copy(new (std::nothrow) u8[romSize]);
	if (!copy) {
		logMessage("Not enough memory for the DS ROM");
		return false;
	}
	memcpy(copy.get(), rom, romSize);
	return loadOwned(std::move(copy), romSize, savePath, dataDir, sampleRate, language, useJit);
}

bool nds_load_fd(int fd, const char* savePath, const char* dataDir, unsigned sampleRate, int language, bool useJit) {
	std::unique_ptr<u8[]> rom;
	size_t size = 0;
	if (fd < 0 || !readAll(fd, rom, size)) {
		return false;
	}
	return loadOwned(std::move(rom), size, savePath, dataDir, sampleRate, language, useJit);
}

void nds_unload(void) {
	NdsSession* session = gNdsSession;
	if (!session) {
		return;
	}
	writeSaveToDisk(session);
	if (session->nds) {
		session->nds->Stop();
		session->nds.reset();
	}
	gNdsSession = nullptr;
	delete session;
}

bool nds_is_loaded(void) {
	return gNdsSession && gNdsSession->nds;
}

void nds_reset(void) {
	NdsSession* session = gNdsSession;
	if (!session || !session->nds) {
		return;
	}
	writeSaveToDisk(session);
	ndsRewindClear(session);
	NDS& nds = *session->nds;
	nds.Reset();
	if (nds.NeedsDirectBoot()) {
		nds.SetupDirectBoot("game.nds");
	}
	setClockToNow(nds);
	nds.Start();
}

void nds_run_frame(void) {
	NdsSession* session = gNdsSession;
	if (!session || !session->nds) {
		return;
	}
	NDS& nds = *session->nds;
	// melonDS wants the mask active low.
	nds.SetKeyMask(~session->keys.load() & 0xFFF);
	if (session->touching) {
		nds.TouchScreen((u16) session->touchX.load(), (u16) session->touchY.load());
	} else {
		nds.ReleaseScreen();
	}
	nds.RunFrame();
	ndsRewindAfterFrame(session);

	bool flush = false;
	{
		std::lock_guard<std::mutex> guard(session->saveLock);
		if (session->saveDirty && ++session->framesSinceSaveChange >= SAVE_SETTLE_FRAMES) {
			flush = true;
		}
	}
	if (flush) {
		writeSaveToDisk(session);
	}
}

void nds_set_keys(uint32_t keys) {
	if (gNdsSession) {
		gNdsSession->keys = keys & 0xFFF;
	}
}

void nds_touch(int x, int y, bool down) {
	NdsSession* session = gNdsSession;
	if (!session) {
		return;
	}
	if (x < 0) x = 0;
	if (x > NDS_SCREEN_WIDTH - 1) x = NDS_SCREEN_WIDTH - 1;
	if (y < 0) y = 0;
	if (y > NDS_SCREEN_HEIGHT - 1) y = NDS_SCREEN_HEIGHT - 1;
	session->touchX = x;
	session->touchY = y;
	session->touching = down;
}

void nds_set_mic_blow(bool blowing) {
	if (gNdsSession) {
		gNdsSession->micBlow = blowing;
	}
}

size_t nds_copy_video(void* dst, size_t dstCapacity, unsigned* width, unsigned* height) {
	if (width) {
		*width = NDS_SCREEN_WIDTH;
	}
	if (height) {
		*height = NDS_SCREEN_HEIGHT * 2;
	}
	NdsSession* session = gNdsSession;
	const size_t pixels = NDS_SCREEN_WIDTH * NDS_SCREEN_HEIGHT;
	if (!session || !session->nds || dstCapacity < pixels * 2 * 4) {
		return 0;
	}
	GPU& gpu = session->nds->GPU;
	int front = gpu.FrontBuffer;
	if (!gpu.Framebuffer[front][0] || !gpu.Framebuffer[front][1]) {
		return 0;
	}
	uint32_t* out = static_cast<uint32_t*>(dst);
	for (int screen = 0; screen < 2; ++screen) {
		// melonDS pixels are 0xAARRGGBB; GL and Android bitmaps want R,G,B,A bytes.
		const u32* in = gpu.Framebuffer[front][screen].get();
		for (size_t i = 0; i < pixels; ++i) {
			u32 p = in[i];
			out[i] = (p & 0x0000FF00u) | ((p >> 16) & 0xFFu) | ((p & 0xFFu) << 16) | 0xFF000000u;
		}
		out += pixels;
	}
	return pixels * 2 * 4;
}

/* Slow motion: plays `in` (n stereo frames) 1/speed times longer by linear
 * interpolation, carrying the position and last sample between calls. */
static size_t stretchAudio(NdsSession* session, const s16* in, int n, int16_t* out, size_t maxFrames) {
	const double step = session->slowSpeed;
	double pos = session->stretchPos;
	size_t produced = 0;
	while (pos < n - 1 && produced < maxFrames) {
		int i = (int) floor(pos);
		double frac = pos - i;
		s16 l0 = i < 0 ? session->lastLeft : in[i * 2];
		s16 r0 = i < 0 ? session->lastRight : in[i * 2 + 1];
		s16 l1 = in[(i + 1) * 2];
		s16 r1 = in[(i + 1) * 2 + 1];
		out[produced * 2] = (int16_t) (l0 + (l1 - l0) * frac);
		out[produced * 2 + 1] = (int16_t) (r0 + (r1 - r0) * frac);
		++produced;
		pos += step;
	}
	// Normally in [-1, 0); lower only if `out` filled up first, then just
	// start over with the next batch.
	session->stretchPos = pos - n < -1.0 ? -1.0 : pos - n;
	session->lastLeft = in[(n - 1) * 2];
	session->lastRight = in[(n - 1) * 2 + 1];
	return produced;
}

size_t nds_read_audio(int16_t* dst, size_t maxFrames) {
	NdsSession* session = gNdsSession;
	if (!session || !session->nds) {
		return 0;
	}
	int available = session->nds->SPU.GetOutputSize();
	if (available <= 0) {
		return 0;
	}
	if (session->slowSpeed < 1.0f) {
		static s16 input[4096 * 2];
		if (available > 4096) {
			available = 4096;
		}
		int n = session->nds->SPU.ReadOutput(input, available);
		return n > 0 ? stretchAudio(session, input, n, dst, maxFrames) : 0;
	}
	if ((size_t) available > maxFrames) {
		available = (int) maxFrames;
	}
	return (size_t) session->nds->SPU.ReadOutput(dst, available);
}

void nds_discard_audio(void) {
	NdsSession* session = gNdsSession;
	if (!session || !session->nds) {
		return;
	}
	static s16 scratch[1024 * 2];
	while (session->nds->SPU.GetOutputSize() > 0) {
		if (session->nds->SPU.ReadOutput(scratch, 1024) <= 0) {
			break;
		}
	}
}

void nds_set_audio_speed(float speed) {
	NdsSession* session = gNdsSession;
	if (!session || !session->nds) {
		return;
	}
	if (speed < 0.1f) {
		speed = 0.1f;
	}
	// Fast forward: a lower output rate played back at the normal rate sounds
	// faster, like the GBA side. Slow motion is stretched in nds_read_audio.
	double rate = speed > 1.0f ? session->sampleRate / (double) speed : session->sampleRate;
	session->nds->SPU.SetOutputSampleRate(rate);
	session->slowSpeed = speed < 1.0f ? speed : 1.0f;
	session->stretchPos = -1.0;
}

void nds_flush_save(void) {
	if (gNdsSession) {
		writeSaveToDisk(gNdsSession);
	}
}

void nds_game_title(char* out, size_t outSize) {
	if (!out || outSize == 0) {
		return;
	}
	out[0] = '\0';
	NdsSession* session = gNdsSession;
	if (!session || !session->nds) {
		return;
	}
	const NDSCart::CartCommon* cart = session->nds->GetNDSCart();
	if (!cart) {
		return;
	}
	const char* title = cart->GetHeader().GameTitle;
	size_t o = 0;
	for (size_t i = 0; i < sizeof(cart->GetHeader().GameTitle) && title[i] && o + 1 < outSize; ++i) {
		unsigned char c = (unsigned char) title[i];
		if (c >= 0x20 && c < 0x7F) {
			out[o++] = (char) c;
		}
	}
	while (o > 0 && out[o - 1] == ' ') {
		--o;
	}
	out[o] = '\0';
}

} // extern "C"
