#include "emu_core.h"

/* Must come first: makes our view of mGBA's structs match how libmgba was
 * compiled (threading, enabled cores, ...). */
#include <mgba/flags.h>

#include <mgba/core/blip_buf.h>
#include <mgba/core/cheats.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/interface.h>
#include <mgba/core/log.h>
#include <mgba/core/rewind.h>
#include <mgba/core/serialize.h>
#include <mgba-util/vfs.h>

#ifdef M_CORE_GBA
#include <mgba/gba/interface.h>
#include <mgba/internal/gba/cheats.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/savedata.h>
#endif
#ifdef M_CORE_GB
#include <mgba/gb/interface.h>
#include <mgba/internal/gb/cheats.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/overrides.h>
#endif

#include <ctype.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "GbaEmuCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) (fprintf(stderr, "[emu] " __VA_ARGS__), fputc('\n', stderr))
#define LOGW(...) (fprintf(stderr, "[emu] WARN " __VA_ARGS__), fputc('\n', stderr))
#endif

/* Internal blip buffer size in samples. One GBA frame at 48 kHz is ~804
 * samples; we drain after every frame so this only needs headroom. */
#define AUDIO_BUFFER_SAMPLES 4096

static struct {
	struct mCore* core;
	color_t* video;
	bool configInitialized;

	struct mCoreRewindContext rewind;
	bool rewindEnabled;
	int rewindInterval;
	int rewindCounter;

	struct mRumble rumble;
	struct mRotationSource rotation;
#ifdef M_CORE_GBA
	struct GBALuminanceSource lux;
#endif
	volatile int rumbleRequested;
	volatile float tiltX;
	volatile float tiltY;
	volatile float gyroZ;
	volatile int solarLevel;
} emu;

/* ---------------------------------------------------------------- logging */

static struct mLogger s_logger;

static void _log(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
	(void) logger;
	if (!(level & (mLOG_FATAL | mLOG_ERROR | mLOG_WARN))) {
		return;
	}
	char message[512];
	vsnprintf(message, sizeof(message), format, args);
#ifdef __ANDROID__
	__android_log_print(level & mLOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_ERROR, LOG_TAG, "%s: %s",
	                    mLogCategoryName(category), message);
#else
	fprintf(stderr, "[mgba] %s: %s\n", mLogCategoryName(category), message);
#endif
}

static void _ensureLogger(void) {
	static bool installed = false;
	if (!installed) {
		s_logger.log = _log;
		s_logger.filter = NULL;
		mLogSetDefaultLogger(&s_logger);
		installed = true;
	}
}

/* ------------------------------------------------------------ peripherals */

static void _setRumble(struct mRumble* rumble, int enable) {
	(void) rumble;
	if (enable) {
		emu.rumbleRequested = 1;
	}
}

static int32_t _readTiltX(struct mRotationSource* source) {
	(void) source;
	/* Same scale mGBA's SDL frontend uses for real accelerometers (m/s^2). */
	return (int32_t) (emu.tiltX * -0x2000000);
}

static int32_t _readTiltY(struct mRotationSource* source) {
	(void) source;
	return (int32_t) (emu.tiltY * -0x2000000);
}

static int32_t _readGyroZ(struct mRotationSource* source) {
	(void) source;
	/* rad/s -> mGBA units (see sdl-events.c: zDelta / -20 * 2.2e9). */
	return (int32_t) (emu.gyroZ * -1.1e8f);
}

#ifdef M_CORE_GBA
static uint8_t _readLuminance(struct GBALuminanceSource* lux) {
	(void) lux;
	int level = emu.solarLevel;
	int value = 0x16;
	if (level > 0) {
		if (level > 10) {
			level = 10;
		}
		value += GBA_LUX_LEVELS[level - 1];
	}
	return 0xFF - value;
}
#endif

/* ------------------------------------------------------------------- load */

#ifdef M_CORE_GBA
/*
 * mGBA's save type detection can't tell 64 KiB from 128 KiB flash for games
 * missing from its database. Like VBA/My Boy, look for the save library
 * signature Nintendo's SDK embeds in the ROM. The database (applied on reset)
 * still wins for known games.
 */
static void _detectFlash1M(struct mCore* core) {
	struct GBA* gba = core->board;
	if (gba->memory.savedata.type != SAVEDATA_AUTODETECT || !gba->memory.rom) {
		return;
	}
	static const char signature[] = "FLASH1M_V";
	const size_t sigLen = sizeof(signature) - 1;
	const uint8_t* rom = (const uint8_t*) gba->memory.rom;
	size_t size = gba->pristineRomSize;
	/* The signature is word aligned in every official SDK build. */
	for (size_t i = 0; i + sigLen <= size; i += 4) {
		if (rom[i] == 'F' && memcmp(&rom[i], signature, sigLen) == 0) {
			LOGI("Found %s signature, using 128 KiB flash", signature);
			GBASavedataForceType(&gba->memory.savedata, SAVEDATA_FLASH1M);
			return;
		}
	}
}
#endif

void emu_default_options(struct EmuOptions* opts) {
	memset(opts, 0, sizeof(*opts));
	opts->sampleRate = 48000;
	opts->skipBios = true;
	opts->frameskip = 0;
	opts->idleLoopDetect = true;
	opts->gbColorize = true;
}

bool emu_load(const void* rom, size_t romSize, const char* savePath, const char* biosPath, const struct EmuOptions* opts) {
	_ensureLogger();
	emu_unload();

	struct EmuOptions defaults;
	if (!opts) {
		emu_default_options(&defaults);
		opts = &defaults;
	}

	struct VFile* romVf = VFileMemChunk(rom, romSize);
	if (!romVf) {
		LOGW("Out of memory copying ROM (%zu bytes)", romSize);
		return false;
	}
	struct mCore* core = mCoreFindVF(romVf);
	if (!core) {
		LOGW("Not a GBA/GB/GBC ROM");
		romVf->close(romVf);
		return false;
	}

	mCoreInitConfig(core, NULL);
	if (!core->init(core)) {
		LOGW("Core init failed");
		mCoreConfigDeinit(&core->config);
		free(core);
		romVf->close(romVf);
		return false;
	}

	emu.video = calloc(EMU_MAX_WIDTH * EMU_MAX_HEIGHT, sizeof(color_t));
	core->setVideoBuffer(core, emu.video, EMU_MAX_WIDTH);
	core->setAudioBufferSize(core, AUDIO_BUFFER_SAMPLES);
	blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), opts->sampleRate);
	blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), opts->sampleRate);

	emu.rumble.setRumble = _setRumble;
	emu.rotation.sample = NULL;
	emu.rotation.readTiltX = _readTiltX;
	emu.rotation.readTiltY = _readTiltY;
	emu.rotation.readGyroZ = _readGyroZ;
	core->setPeripheral(core, mPERIPH_RUMBLE, &emu.rumble);
	core->setPeripheral(core, mPERIPH_ROTATION, &emu.rotation);
#ifdef M_CORE_GBA
	if (core->platform(core) == mPLATFORM_GBA) {
		emu.lux.sample = NULL;
		emu.lux.readLuminance = _readLuminance;
		core->setPeripheral(core, mPERIPH_GBA_LUMINANCE, &emu.lux);
	}
#endif

	bool haveBios = biosPath && biosPath[0] && core->platform(core) == mPLATFORM_GBA;
	struct mCoreOptions coreOpts = {
		/* useBios=false stops mGBA from searching ~/.config for a BIOS. */
		.useBios = haveBios,
		.skipBios = opts->skipBios,
		.frameskip = opts->frameskip,
		.volume = 0x100,
		.audioBuffers = AUDIO_BUFFER_SAMPLES,
		.sampleRate = opts->sampleRate,
	};
	mCoreConfigLoadDefaults(&core->config, &coreOpts);
	mCoreConfigSetDefaultValue(&core->config, "idleOptimization", opts->idleLoopDetect ? "detect" : "remove");
#ifdef M_CORE_GB
	mCoreConfigSetDefaultIntValue(&core->config, "sgb.borders", 0);
	mCoreConfigSetDefaultIntValue(&core->config, "gb.colors", opts->gbColorize ? GB_COLORS_CGB : GB_COLORS_NONE);
#endif
	/* Foreign config: apply our table without reading mGBA's config.ini. */
	mCoreLoadForeignConfig(core, &core->config);
	emu.configInitialized = true;

	if (!core->loadROM(core, romVf)) {
		LOGW("loadROM failed");
		romVf->close(romVf);
		emu.core = core;
		emu_unload();
		return false;
	}

	if (savePath && savePath[0]) {
		struct VFile* save = VFileOpen(savePath, O_CREAT | O_RDWR);
		if (!save || !core->loadSave(core, save)) {
			LOGW("Could not open save file %s; using a temporary one", savePath);
			if (save) {
				save->close(save);
			}
			core->loadTemporarySave(core, VFileMemChunk(NULL, 0));
		}
	}

	if (haveBios) {
		struct VFile* bios = VFileOpen(biosPath, O_RDONLY);
		if (bios && !core->loadBIOS(core, bios, 0)) {
			LOGW("BIOS rejected: %s", biosPath);
			bios->close(bios);
		} else if (!bios) {
			LOGW("BIOS not found: %s", biosPath);
		}
	}

#ifdef M_CORE_GBA
	if (core->platform(core) == mPLATFORM_GBA) {
		_detectFlash1M(core);
	}
#endif

	core->reset(core);
	emu.core = core;
	emu.rumbleRequested = 0;
	emu.rewindCounter = 0;

	char title[32] = { 0 };
	char code[32] = { 0 };
	core->getGameTitle(core, title);
	core->getGameCode(core, code);
	LOGI("Loaded %s [%s], platform %d", title, code, core->platform(core));
	return true;
}

void emu_unload(void) {
	struct mCore* core = emu.core;
	if (emu.rewindEnabled) {
		mCoreRewindContextDeinit(&emu.rewind);
		memset(&emu.rewind, 0, sizeof(emu.rewind));
		emu.rewindEnabled = false;
	}
	if (core) {
		/* deinit flushes battery saves and frees the ROM. */
		mCoreConfigDeinit(&core->config);
		core->deinit(core);
		emu.core = NULL;
	}
	free(emu.video);
	emu.video = NULL;
	emu.configInitialized = false;
}

bool emu_is_loaded(void) {
	return emu.core != NULL;
}

int emu_platform(void) {
	if (!emu.core) {
		return EMU_PLATFORM_NONE;
	}
	return emu.core->platform(emu.core);
}

/* -------------------------------------------------------------- execution */

void emu_reset(void) {
	if (emu.core) {
		emu.core->reset(emu.core);
	}
}

void emu_run_frame(void) {
	struct mCore* core = emu.core;
	if (!core) {
		return;
	}
	if (emu.rewindEnabled) {
		if (++emu.rewindCounter >= emu.rewindInterval) {
			emu.rewindCounter = 0;
			mCoreRewindAppend(&emu.rewind, core);
		}
	}
	core->runFrame(core);
}

void emu_set_keys(uint32_t keys) {
	if (emu.core) {
		emu.core->setKeys(emu.core, keys & 0x3FF);
	}
}

void emu_set_frameskip(int frameskip) {
	struct mCore* core = emu.core;
	if (!core) {
		return;
	}
	mCoreConfigSetIntValue(&core->config, "frameskip", frameskip);
	core->opts.frameskip = frameskip;
	core->reloadConfigOption(core, NULL, NULL);
}

/* ------------------------------------------------------------------ video */

void emu_video_size(unsigned* width, unsigned* height) {
	if (!emu.core) {
		*width = 240;
		*height = 160;
		return;
	}
	emu.core->desiredVideoDimensions(emu.core, width, height);
}

const uint32_t* emu_video_buffer(size_t* stridePixels) {
	if (stridePixels) {
		*stridePixels = EMU_MAX_WIDTH;
	}
	return (const uint32_t*) emu.video;
}

size_t emu_copy_video(void* dst, size_t dstCapacity, unsigned* width, unsigned* height) {
	unsigned w, h;
	emu_video_size(&w, &h);
	if (width) {
		*width = w;
	}
	if (height) {
		*height = h;
	}
	if (!emu.video) {
		return 0;
	}
	size_t rowBytes = (size_t) w * sizeof(uint32_t);
	if (dstCapacity < rowBytes * h) {
		return 0;
	}
	uint32_t* out = dst;
	for (unsigned y = 0; y < h; ++y) {
		const color_t* row = &emu.video[y * EMU_MAX_WIDTH];
		for (unsigned x = 0; x < w; ++x) {
			out[x] = (uint32_t) row[x] | 0xFF000000u;
		}
		out += w;
	}
	return rowBytes * h;
}

/* ------------------------------------------------------------------ audio */

size_t emu_read_audio(int16_t* dst, size_t maxFrames) {
	struct mCore* core = emu.core;
	if (!core) {
		return 0;
	}
	blip_t* left = core->getAudioChannel(core, 0);
	blip_t* right = core->getAudioChannel(core, 1);
	int available = blip_samples_avail(left);
	if (available <= 0) {
		return 0;
	}
	if ((size_t) available > maxFrames) {
		available = (int) maxFrames;
	}
	int produced = blip_read_samples(left, dst, available, true);
	blip_read_samples(right, dst + 1, available, true);
	return (size_t) produced;
}

void emu_discard_audio(void) {
	struct mCore* core = emu.core;
	if (!core) {
		return;
	}
	/* blip_buf has no "skip" call: read into scratch space and drop it. */
	static int16_t scratch[1024];
	blip_t* left = core->getAudioChannel(core, 0);
	blip_t* right = core->getAudioChannel(core, 1);
	int available;
	while ((available = blip_samples_avail(left)) > 0) {
		int count = available > 1024 ? 1024 : available;
		blip_read_samples(left, scratch, count, false);
		blip_read_samples(right, scratch, count, false);
	}
}

/* ------------------------------------------------------------ save states */

bool emu_save_state(const char* path) {
	struct mCore* core = emu.core;
	if (!core || !path) {
		return false;
	}
	/* Write to a temp file first so a crash never leaves a broken slot. */
	char tmpPath[1024];
	snprintf(tmpPath, sizeof(tmpPath), "%s.tmp", path);
	struct VFile* vf = VFileOpen(tmpPath, O_CREAT | O_TRUNC | O_RDWR);
	if (!vf) {
		LOGW("Cannot open %s for writing", tmpPath);
		return false;
	}
	/* Battery data is embedded so loading the state keeps the game consistent;
	 * mGBA only writes it back to the .sav once the game saves again. */
	bool ok = mCoreSaveStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA);
	vf->close(vf);
	if (!ok || rename(tmpPath, path) != 0) {
		remove(tmpPath);
		LOGW("Saving state to %s failed", path);
		return false;
	}
	return true;
}

bool emu_load_state(const char* path) {
	struct mCore* core = emu.core;
	if (!core || !path) {
		return false;
	}
	struct VFile* vf = VFileOpen(path, O_RDONLY);
	if (!vf) {
		return false;
	}
	bool ok = mCoreLoadStateNamed(core, vf, SAVESTATE_RTC);
	vf->close(vf);
	if (!ok) {
		LOGW("Loading state %s failed", path);
	}
	return ok;
}

/* ----------------------------------------------------------------- cheats */

static int _cheatTypeFor(int platform, int type) {
#ifdef M_CORE_GBA
	if (platform == mPLATFORM_GBA) {
		switch (type) {
		case EMU_CHEAT_CODEBREAKER:
			return GBA_CHEAT_CODEBREAKER;
		case EMU_CHEAT_GAMESHARK:
			return GBA_CHEAT_GAMESHARK;
		case EMU_CHEAT_ACTION_REPLAY:
			return GBA_CHEAT_PRO_ACTION_REPLAY;
		case EMU_CHEAT_RAW:
			return GBA_CHEAT_VBA;
		default:
			return GBA_CHEAT_AUTODETECT;
		}
	}
#endif
#ifdef M_CORE_GB
	if (platform == mPLATFORM_GB) {
		switch (type) {
		case EMU_CHEAT_GAMESHARK:
			return GB_CHEAT_GAMESHARK;
		case EMU_CHEAT_GAME_GENIE:
			return GB_CHEAT_GAME_GENIE;
		case EMU_CHEAT_RAW:
			return GB_CHEAT_VBA;
		default:
			return GB_CHEAT_AUTODETECT;
		}
	}
#endif
	(void) type;
	return 0;
}

/*
 * Normalises one line of a GBA code: uppercase hex, and inserts the space that
 * people often omit ("XXXXXXXXYYYYYYYY" -> "XXXXXXXX YYYYYYYY",
 * "XXXXXXXXYYYY" -> "XXXXXXXX YYYY").
 */
static void _normalizeGbaLine(const char* in, char* out, size_t outSize) {
	char compact[64];
	size_t n = 0;
	bool hasSeparator = false;
	for (const char* c = in; *c && n < sizeof(compact) - 1; ++c) {
		if (*c == ':') {
			hasSeparator = true;
		}
		if (isspace((unsigned char) *c)) {
			continue;
		}
		compact[n++] = (char) toupper((unsigned char) *c);
	}
	compact[n] = '\0';
	if (!hasSeparator && (n == 16 || n == 12)) {
		snprintf(out, outSize, "%.8s %s", compact, &compact[8]);
		return;
	}
	/* Keep the user's own spacing otherwise (e.g. VBA "AAAAAAAA:VV"). */
	size_t i = 0;
	for (const char* c = in; *c && i < outSize - 1; ++c) {
		out[i++] = (char) toupper((unsigned char) *c);
	}
	out[i] = '\0';
	/* Trim trailing whitespace. */
	while (i > 0 && isspace((unsigned char) out[i - 1])) {
		out[--i] = '\0';
	}
}

void emu_cheats_clear(void) {
	if (!emu.core) {
		return;
	}
	struct mCheatDevice* device = emu.core->cheatDevice(emu.core);
	/* Remove sets one by one so ROM hooks/patches get undone properly. */
	while (mCheatSetsSize(&device->cheats)) {
		struct mCheatSet* set = *mCheatSetsGetPointer(&device->cheats, mCheatSetsSize(&device->cheats) - 1);
		mCheatRemoveSet(device, set);
		mCheatSetDeinit(set);
	}
}

bool emu_cheats_add(const char* name, const char* code, int type, bool enabled) {
	struct mCore* core = emu.core;
	if (!core || !code) {
		return false;
	}
	struct mCheatDevice* device = core->cheatDevice(core);
	if (!device) {
		return false;
	}
	struct mCheatSet* set = device->createSet(device, name);
	int platform = core->platform(core);
	int coreType = _cheatTypeFor(platform, type);
	bool anyLine = false;
	bool allOk = true;

	char* copy = strdup(code);
	char* saveptr = NULL;
	for (char* line = strtok_r(copy, "\r\n;", &saveptr); line; line = strtok_r(NULL, "\r\n;", &saveptr)) {
		while (isspace((unsigned char) *line)) {
			++line;
		}
		if (!*line) {
			continue;
		}
		char normalized[96];
		if (platform == mPLATFORM_GBA) {
			_normalizeGbaLine(line, normalized, sizeof(normalized));
		} else {
			snprintf(normalized, sizeof(normalized), "%s", line);
		}
		if (mCheatAddLine(set, normalized, coreType)) {
			anyLine = true;
		} else {
			LOGW("Rejected cheat line '%s'", normalized);
			allOk = false;
		}
	}
	free(copy);

	if (!anyLine) {
		mCheatSetDeinit(set); /* also frees the set */
		return false;
	}
	set->enabled = enabled;
	mCheatAddSet(device, set);
	if (set->refresh) {
		set->refresh(set, device);
	}
	return allOk;
}

/* ----------------------------------------------------------------- rewind */

void emu_set_rewind(bool enabled, int capacity, int interval) {
	if (emu.rewindEnabled) {
		mCoreRewindContextDeinit(&emu.rewind);
		memset(&emu.rewind, 0, sizeof(emu.rewind));
		emu.rewindEnabled = false;
	}
	if (!enabled || capacity <= 0) {
		return;
	}
	memset(&emu.rewind, 0, sizeof(emu.rewind));
#ifdef DISABLE_THREADING
	mCoreRewindContextInit(&emu.rewind, (size_t) capacity, false);
#else
	/* Diffing happens on a helper thread so the frame loop stays fast. */
	mCoreRewindContextInit(&emu.rewind, (size_t) capacity, true);
#endif
	emu.rewindInterval = interval < 1 ? 1 : interval;
	emu.rewindCounter = 0;
	emu.rewindEnabled = true;
}

bool emu_rewind_step(void) {
	struct mCore* core = emu.core;
	if (!core || !emu.rewindEnabled) {
		return false;
	}
	if (!mCoreRewindRestore(&emu.rewind, core)) {
		return false;
	}
	/* Run one frame so the screen shows the restored moment. */
	core->runFrame(core);
	emu.rewindCounter = 0;
	return true;
}

/* ---------------------------------------------------------------- sensors */

void emu_set_tilt(float x, float y) {
	emu.tiltX = x;
	emu.tiltY = y;
}

void emu_set_gyro(float z) {
	emu.gyroZ = z;
}

void emu_set_solar_level(int level) {
	emu.solarLevel = level < 0 ? 0 : (level > 10 ? 10 : level);
}

bool emu_poll_rumble(void) {
	bool requested = emu.rumbleRequested != 0;
	emu.rumbleRequested = 0;
	return requested;
}

/* ------------------------------------------------------------------- info */

int emu_hardware(void) {
	struct mCore* core = emu.core;
	if (!core) {
		return 0;
	}
	int features = 0;
#ifdef M_CORE_GBA
	if (core->platform(core) == mPLATFORM_GBA) {
		uint32_t devices = ((struct GBA*) core->board)->memory.hw.devices;
		if (devices & HW_RTC) {
			features |= EMU_HW_RTC;
		}
		if (devices & HW_RUMBLE) {
			features |= EMU_HW_RUMBLE;
		}
		if (devices & HW_LIGHT_SENSOR) {
			features |= EMU_HW_LIGHT_SENSOR;
		}
		if (devices & HW_GYRO) {
			features |= EMU_HW_GYRO;
		}
		if (devices & HW_TILT) {
			features |= EMU_HW_TILT;
		}
	}
#endif
#ifdef M_CORE_GB
	if (core->platform(core) == mPLATFORM_GB) {
		enum GBMemoryBankControllerType mbc = ((struct GB*) core->board)->memory.mbcType;
		if (mbc == GB_MBC7) {
			features |= EMU_HW_TILT;
		}
		if (mbc == GB_MBC5_RUMBLE) {
			features |= EMU_HW_RUMBLE;
		}
	}
#endif
	return features;
}

/* ROM headers may contain arbitrary bytes; keep printable ASCII only so the
 * result is always valid (modified) UTF-8 for JNI. */
static void _copyPrintable(char* out, size_t outSize, const char* in) {
	size_t o = 0;
	for (size_t i = 0; in[i] && o + 1 < outSize; ++i) {
		unsigned char c = (unsigned char) in[i];
		if (c >= 0x20 && c < 0x7F) {
			out[o++] = (char) c;
		}
	}
	while (o > 0 && out[o - 1] == ' ') {
		--o;
	}
	out[o] = '\0';
}

void emu_game_title(char* out, size_t outSize) {
	char title[32] = { 0 };
	if (emu.core) {
		emu.core->getGameTitle(emu.core, title);
	}
	_copyPrintable(out, outSize, title);
}

void emu_game_code(char* out, size_t outSize) {
	char code[32] = { 0 };
	if (emu.core) {
		emu.core->getGameCode(emu.core, code);
	}
	_copyPrintable(out, outSize, code);
}
