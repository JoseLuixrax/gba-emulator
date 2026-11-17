// A small libretro frontend for the 3DS core (Azahar). It loads the core,
// answers its environment requests, keeps its options, collects audio and
// feeds input. The platform layer (Android thread or desktop test) owns the
// GL contexts and calls in from a single thread, except for the input
// setters which only store values.
#ifndef N3DS_HOST_H
#define N3DS_HOST_H

#include <atomic>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "libretro.h"
#include "n3ds_gl.h"

namespace n3ds {

struct OptionInfo {
	std::string key;
	std::string defaultValue;
	std::vector<std::string> values;
};

class Host {
public:
	static Host& get();

	// dlopen()s the core (a path, or on Android just the library name) and
	// calls retro_init. Directories must be set first.
	bool open(const std::string& corePath, std::string& error);
	void close();

	void setDirectories(const std::string& systemDir, const std::string& saveDir);
	// Before or during a game; unknown values fall back to the default.
	void setOption(const std::string& key, const std::string& value);
	const std::vector<OptionInfo>& options() const { return optionList; }

	// retro_load_game. The game is really loaded once the GL context exists
	// (startRenderer). `path` is what the core sees (see n3ds_vfs).
	bool loadGame(const std::string& path, std::string& error);
	void unloadGame();

	// What the core asked for in SET_HW_RENDER.
	bool hwRequested() const { return hwSet; }
	retro_hw_context_type hwContextType() const { return hw.context_type; }
	unsigned hwVersionMajor() const { return hw.version_major; }
	unsigned hwVersionMinor() const { return hw.version_minor; }

	// With the core's context current: creates the framebuffer and calls the
	// core's context_reset (which is where Azahar loads the game).
	bool startRenderer(std::string& error);
	void stopRenderer();

	// One emulated frame. Core context current.
	void runFrame();
	void reset();

	const Target& target() const { return fb; }
	unsigned frameWidth() const { return lastWidth; }
	unsigned frameHeight() const { return lastHeight; }
	uint64_t framesShown() const { return shownFrames; }
	double sampleRate() const { return avInfo.timing.sample_rate; }
	double fps() const { return avInfo.timing.fps; }

	// Audio produced since the last call (interleaved stereo).
	void takeAudio(std::vector<int16_t>& out);

	// Input. buttons: RETRO_DEVICE_ID_JOYPAD_* bits. Axes -32768..32767.
	// Pointer coordinates are libretro's -0x7FFF..0x7FFF over the whole frame.
	void setButtons(uint32_t mask) { buttons = mask; }
	void setAnalog(int stick, int16_t x, int16_t y);
	void setPointer(int16_t x, int16_t y, bool pressed);

	bool saveState(std::vector<uint8_t>& out);
	bool loadState(const std::vector<uint8_t>& data);

	// Last message the core wanted to show (e.g. "this ROM is encrypted").
	std::string lastMessage() const { return message; }
	bool shutdownRequested() const { return shutdown; }

	// Called from the static libretro callbacks.
	bool environment(unsigned cmd, void* data);
	void videoRefresh(const void* data, unsigned width, unsigned height);
	void audioBatch(const int16_t* data, size_t frames);
	int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id);

private:
	Host() = default;
	bool loadSymbols(std::string& error);
	void registerOptions(const retro_core_option_v2_definition* defs);
	void registerOptionsV1(const retro_core_option_definition* defs);
	void registerVariables(const retro_variable* vars);
	const char* optionValue(const std::string& key);

	struct Api {
		void (*set_environment)(retro_environment_t);
		void (*set_video_refresh)(retro_video_refresh_t);
		void (*set_audio_sample)(retro_audio_sample_t);
		void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
		void (*set_input_poll)(retro_input_poll_t);
		void (*set_input_state)(retro_input_state_t);
		void (*init)();
		void (*deinit)();
		void (*get_system_av_info)(retro_system_av_info*);
		void (*set_controller_port_device)(unsigned, unsigned);
		void (*reset)();
		void (*run)();
		size_t (*serialize_size)();
		bool (*serialize)(void*, size_t);
		bool (*unserialize)(const void*, size_t);
		bool (*load_game)(const retro_game_info*);
		void (*unload_game)();
	};

	void* library = nullptr;
	Api api{};
	bool initialized = false;
	bool gameLoaded = false;
	bool rendererStarted = false;

	std::string systemDir;
	std::string saveDir;
	std::string gamePath;

	std::vector<OptionInfo> optionList;
	std::map<std::string, std::string> overrides;
	std::map<std::string, std::string> currentValues;
	bool optionsChanged = false;

	retro_hw_render_callback* coreHw = nullptr; // the core's own struct
	retro_hw_render_callback hw{};
	bool hwSet = false;
	retro_frame_time_callback frameTime{};
	bool hasFrameTime = false;
	retro_system_av_info avInfo{};
	unsigned geometryWidth = 0;
	unsigned geometryHeight = 0;

	Target fb;
	unsigned lastWidth = 0;
	unsigned lastHeight = 0;
	uint64_t shownFrames = 0;

	std::vector<int16_t> audio;

	std::atomic<uint32_t> buttons{0};
	std::atomic<int16_t> analog[2][2] = {};
	std::atomic<int16_t> pointerX{0};
	std::atomic<int16_t> pointerY{0};
	std::atomic<bool> pointerPressed{false};

	std::string message;
	bool shutdown = false;
};

} // namespace n3ds

#endif
