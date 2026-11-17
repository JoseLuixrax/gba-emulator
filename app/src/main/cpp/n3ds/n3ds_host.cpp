#include "n3ds_host.h"

#include <dlfcn.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "n3ds_vfs.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace n3ds {

namespace {

void logLine(int level, const char* text) {
#ifdef __ANDROID__
	int prio = level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : (level >= RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO);
	__android_log_write(prio, "azahar", text);
#else
	if (level >= RETRO_LOG_WARN) {
		fputs(text, stderr);
	}
#endif
}

void RETRO_CALLCONV coreLog(enum retro_log_level level, const char* fmt, ...) {
	if (level == RETRO_LOG_DEBUG) {
		return;
	}
	char line[1024];
	va_list args;
	va_start(args, fmt);
	vsnprintf(line, sizeof(line), fmt, args);
	va_end(args);
	logLine(level, line);
}

bool RETRO_CALLCONV onEnvironment(unsigned cmd, void* data) {
	return Host::get().environment(cmd, data);
}

void RETRO_CALLCONV onVideoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
	Host::get().videoRefresh(data, width, height);
}

void RETRO_CALLCONV onAudioSample(int16_t left, int16_t right) {
	int16_t frame[2] = {left, right};
	Host::get().audioBatch(frame, 1);
}

size_t RETRO_CALLCONV onAudioBatch(const int16_t* data, size_t frames) {
	Host::get().audioBatch(data, frames);
	return frames;
}

void RETRO_CALLCONV onInputPoll() {}

int16_t RETRO_CALLCONV onInputState(unsigned port, unsigned device, unsigned index, unsigned id) {
	return Host::get().inputState(port, device, index, id);
}

uintptr_t RETRO_CALLCONV currentFramebuffer() {
	return Host::get().target().fbo;
}

retro_proc_address_t RETRO_CALLCONV procAddress(const char* name) {
	return reinterpret_cast<retro_proc_address_t>(glProc(name));
}

} // namespace

Host& Host::get() {
	static Host host;
	return host;
}

bool Host::loadSymbols(std::string& error) {
	struct Entry {
		const char* name;
		void** slot;
	};
	Entry entries[] = {
		{"retro_set_environment", (void**) &api.set_environment},
		{"retro_set_video_refresh", (void**) &api.set_video_refresh},
		{"retro_set_audio_sample", (void**) &api.set_audio_sample},
		{"retro_set_audio_sample_batch", (void**) &api.set_audio_sample_batch},
		{"retro_set_input_poll", (void**) &api.set_input_poll},
		{"retro_set_input_state", (void**) &api.set_input_state},
		{"retro_init", (void**) &api.init},
		{"retro_deinit", (void**) &api.deinit},
		{"retro_get_system_av_info", (void**) &api.get_system_av_info},
		{"retro_set_controller_port_device", (void**) &api.set_controller_port_device},
		{"retro_reset", (void**) &api.reset},
		{"retro_run", (void**) &api.run},
		{"retro_serialize_size", (void**) &api.serialize_size},
		{"retro_serialize", (void**) &api.serialize},
		{"retro_unserialize", (void**) &api.unserialize},
		{"retro_load_game", (void**) &api.load_game},
		{"retro_unload_game", (void**) &api.unload_game},
	};
	for (const Entry& entry : entries) {
		*entry.slot = dlsym(library, entry.name);
		if (!*entry.slot) {
			error = std::string("The 3DS core has no ") + entry.name;
			return false;
		}
	}
	return true;
}

bool Host::open(const std::string& corePath, std::string& error) {
	if (library) {
		return true;
	}
	library = dlopen(corePath.c_str(), RTLD_NOW | RTLD_LOCAL);
	if (!library) {
		const char* why = dlerror();
		error = std::string("Cannot load the 3DS core: ") + (why ? why : corePath);
		return false;
	}
	if (!loadSymbols(error)) {
		dlclose(library);
		library = nullptr;
		return false;
	}
	api.set_environment(onEnvironment);
	api.init();
	api.set_video_refresh(onVideoRefresh);
	api.set_audio_sample(onAudioSample);
	api.set_audio_sample_batch(onAudioBatch);
	api.set_input_poll(onInputPoll);
	api.set_input_state(onInputState);
	initialized = true;
	return true;
}

void Host::close() {
	unloadGame();
	if (initialized) {
		api.deinit();
		initialized = false;
	}
	// The core keeps global state and threads around; it stays mapped for
	// the rest of the process (the app runs each 3DS game in its own one).
	library = nullptr;
}

void Host::setDirectories(const std::string& system, const std::string& save) {
	systemDir = system;
	saveDir = save;
}

void Host::setOption(const std::string& key, const std::string& value) {
	overrides[key] = value;
	currentValues.erase(key);
	optionsChanged = true;
}

const char* Host::optionValue(const std::string& key) {
	auto cached = currentValues.find(key);
	if (cached != currentValues.end()) {
		return cached->second.c_str();
	}
	auto info = std::find_if(optionList.begin(), optionList.end(), [&](const OptionInfo& o) { return o.key == key; });
	auto wanted = overrides.find(key);
	std::string value;
	if (info != optionList.end()) {
		value = info->defaultValue;
		if (wanted != overrides.end() &&
		    std::find(info->values.begin(), info->values.end(), wanted->second) != info->values.end()) {
			value = wanted->second;
		}
	} else if (wanted != overrides.end()) {
		value = wanted->second;
	} else {
		return nullptr;
	}
	return (currentValues[key] = value).c_str();
}

void Host::registerOptions(const retro_core_option_v2_definition* defs) {
	optionList.clear();
	currentValues.clear();
	for (; defs && defs->key; ++defs) {
		OptionInfo info;
		info.key = defs->key;
		for (int i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && defs->values[i].value; ++i) {
			info.values.push_back(defs->values[i].value);
		}
		info.defaultValue = defs->default_value ? defs->default_value : (info.values.empty() ? "" : info.values[0]);
		optionList.push_back(info);
	}
}

void Host::registerOptionsV1(const retro_core_option_definition* defs) {
	optionList.clear();
	currentValues.clear();
	for (; defs && defs->key; ++defs) {
		OptionInfo info;
		info.key = defs->key;
		for (int i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && defs->values[i].value; ++i) {
			info.values.push_back(defs->values[i].value);
		}
		info.defaultValue = defs->default_value ? defs->default_value : (info.values.empty() ? "" : info.values[0]);
		optionList.push_back(info);
	}
}

void Host::registerVariables(const retro_variable* vars) {
	optionList.clear();
	currentValues.clear();
	// "Description; first|second|third", the first value is the default.
	for (; vars && vars->key; ++vars) {
		OptionInfo info;
		info.key = vars->key;
		std::string spec = vars->value ? vars->value : "";
		size_t semicolon = spec.find("; ");
		std::string list = semicolon == std::string::npos ? spec : spec.substr(semicolon + 2);
		size_t start = 0;
		while (start <= list.size()) {
			size_t bar = list.find('|', start);
			info.values.push_back(list.substr(start, bar == std::string::npos ? std::string::npos : bar - start));
			if (bar == std::string::npos) {
				break;
			}
			start = bar + 1;
		}
		info.defaultValue = info.values.empty() ? "" : info.values[0];
		optionList.push_back(info);
	}
}

bool Host::environment(unsigned cmd, void* data) {
	if (getenv("N3DS_ENV_LOG")) {
		fprintf(stderr, "env %u (0x%x)\n", cmd & 0xFFFF, cmd);
	}
	switch (cmd) {
	case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
		static_cast<retro_log_callback*>(data)->log = coreLog;
		return true;
	case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
		*static_cast<const char**>(data) = systemDir.c_str();
		return true;
	case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
		*static_cast<const char**>(data) = saveDir.c_str();
		return true;
	case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
		return *static_cast<const retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
	case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
#ifdef __ANDROID__
		*static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_OPENGLES3;
#else
		*static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_OPENGL_CORE;
#endif
		return true;
	case RETRO_ENVIRONMENT_SET_HW_RENDER: {
		auto* request = static_cast<retro_hw_render_callback*>(data);
		switch (request->context_type) {
		case RETRO_HW_CONTEXT_OPENGLES3:
		case RETRO_HW_CONTEXT_OPENGLES_VERSION:
		case RETRO_HW_CONTEXT_OPENGL_CORE:
		case RETRO_HW_CONTEXT_OPENGL:
			break;
		default:
			return false; // no Vulkan here
		}
		request->get_current_framebuffer = currentFramebuffer;
		request->get_proc_address = procAddress;
		coreHw = request;
		hw = *request;
		hwSet = true;
		return true;
	}
	case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
		// The platform layer always gives the core a context of its own.
		return true;
	case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
		*static_cast<unsigned*>(data) = 2;
		return true;
	case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
		registerOptions(static_cast<const retro_core_options_v2*>(data)->definitions);
		return true;
	case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
		registerOptions(static_cast<const retro_core_options_v2_intl*>(data)->us->definitions);
		return true;
	case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
		registerOptionsV1(static_cast<const retro_core_option_definition*>(data));
		return true;
	case RETRO_ENVIRONMENT_SET_VARIABLES:
		registerVariables(static_cast<const retro_variable*>(data));
		return true;
	case RETRO_ENVIRONMENT_GET_VARIABLE: {
		auto* var = static_cast<retro_variable*>(data);
		var->value = var->key ? optionValue(var->key) : nullptr;
		return var->value != nullptr;
	}
	case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
		*static_cast<bool*>(data) = optionsChanged;
		optionsChanged = false;
		return true;
	case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
	case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
		return true;
	case RETRO_ENVIRONMENT_GET_VFS_INTERFACE: {
		auto* info = static_cast<retro_vfs_interface_info*>(data);
		if (info->required_interface_version > n3dsVfsVersion()) {
			return false;
		}
		info->required_interface_version = n3dsVfsVersion();
		info->iface = const_cast<retro_vfs_interface*>(n3dsVfsInterface());
		return true;
	}
	case RETRO_ENVIRONMENT_SET_GEOMETRY: {
		auto* geometry = static_cast<const retro_game_geometry*>(data);
		geometryWidth = geometry->base_width;
		geometryHeight = geometry->base_height;
		return true;
	}
	case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
		avInfo = *static_cast<const retro_system_av_info*>(data);
		geometryWidth = avInfo.geometry.base_width;
		geometryHeight = avInfo.geometry.base_height;
		return true;
	}
	case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
		frameTime = *static_cast<const retro_frame_time_callback*>(data);
		hasFrameTime = frameTime.callback != nullptr;
		return true;
	case RETRO_ENVIRONMENT_SET_MESSAGE: {
		const auto* msg = static_cast<const retro_message*>(data);
		if (msg && msg->msg) {
			message = msg->msg;
			logLine(RETRO_LOG_WARN, message.c_str());
		}
		return true;
	}
	case RETRO_ENVIRONMENT_SHUTDOWN:
		shutdown = true;
		return true;
	case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
		return true;
	case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
	case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
	case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
	case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
	case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
		return true;
	default:
		// Audio callback mode, Vulkan, sensors, microphone...: not offered.
		return false;
	}
}

bool Host::loadGame(const std::string& path, std::string& error) {
	if (!initialized) {
		error = "The 3DS core isn't loaded";
		return false;
	}
	unloadGame();
	message.clear();
	shutdown = false;
	hwSet = false;
	coreHw = nullptr;
	gamePath = path;
	retro_game_info info{};
	info.path = gamePath.c_str();
	if (!api.load_game(&info)) {
		error = message.empty() ? "The 3DS core couldn't open this game" : message;
		return false;
	}
	gameLoaded = true;
	api.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
	api.get_system_av_info(&avInfo);
	if (!geometryWidth) {
		geometryWidth = avInfo.geometry.base_width;
		geometryHeight = avInfo.geometry.base_height;
	}
	if (!hwSet) {
		error = "The 3DS core didn't ask for an OpenGL context";
		return false;
	}
	return true;
}

bool Host::startRenderer(std::string& error) {
	if (!gameLoaded || !hwSet) {
		error = "No game loaded";
		return false;
	}
	if (!glLoad()) {
		error = "OpenGL ES 3 isn't fully available";
		return false;
	}
	if (!targetResize(fb, std::max(geometryWidth, 1u), std::max(geometryHeight, 1u))) {
		error = "Cannot create the framebuffer";
		return false;
	}
	message.clear();
	if (coreHw && coreHw->context_reset) {
		coreHw->context_reset();
	}
	rendererStarted = true;
	return true;
}

void Host::stopRenderer() {
	if (rendererStarted && coreHw && coreHw->context_destroy) {
		coreHw->context_destroy();
	}
	rendererStarted = false;
	targetDestroy(fb);
}

void Host::unloadGame() {
	if (!gameLoaded) {
		return;
	}
	api.unload_game();
	gameLoaded = false;
	rendererStarted = false;
}

void Host::runFrame() {
	if (!gameLoaded || !rendererStarted) {
		return;
	}
	// A layout or resolution change asks for a different frame size.
	if (geometryWidth && geometryHeight && (geometryWidth != fb.width || geometryHeight != fb.height)) {
		targetResize(fb, geometryWidth, geometryHeight);
	}
	if (hasFrameTime) {
		frameTime.callback(frameTime.reference);
	}
	api.run();
}

void Host::reset() {
	if (gameLoaded) {
		api.reset();
	}
}

void Host::videoRefresh(const void* data, unsigned width, unsigned height) {
	if (!data) {
		return; // same frame as before
	}
	lastWidth = std::min(width, fb.width);
	lastHeight = std::min(height, fb.height);
	++shownFrames;
}

void Host::audioBatch(const int16_t* data, size_t frames) {
	audio.insert(audio.end(), data, data + frames * 2);
}

void Host::takeAudio(std::vector<int16_t>& out) {
	out.swap(audio);
	audio.clear();
}

void Host::setAnalog(int stick, int16_t x, int16_t y) {
	if (stick < 0 || stick > 1) {
		return;
	}
	analog[stick][0] = x;
	analog[stick][1] = y;
}

void Host::setPointer(int16_t x, int16_t y, bool pressed) {
	pointerX = x;
	pointerY = y;
	pointerPressed = pressed;
}

int16_t Host::inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
	if (port != 0) {
		return 0;
	}
	switch (device) {
	case RETRO_DEVICE_JOYPAD:
		if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
			return (int16_t) (buttons.load() & 0xFFFF);
		}
		return id < 16 && (buttons.load() >> id) & 1 ? 1 : 0;
	case RETRO_DEVICE_ANALOG:
		if (index <= RETRO_DEVICE_INDEX_ANALOG_RIGHT && id <= RETRO_DEVICE_ID_ANALOG_Y) {
			return analog[index][id];
		}
		return 0;
	case RETRO_DEVICE_POINTER:
		if (index != 0) {
			return 0;
		}
		switch (id) {
		case RETRO_DEVICE_ID_POINTER_X:
			return pointerX;
		case RETRO_DEVICE_ID_POINTER_Y:
			return pointerY;
		case RETRO_DEVICE_ID_POINTER_PRESSED:
			return pointerPressed ? 1 : 0;
		case RETRO_DEVICE_ID_POINTER_COUNT:
			return pointerPressed ? 1 : 0;
		default:
			return 0;
		}
	default:
		return 0;
	}
}

bool Host::saveState(std::vector<uint8_t>& out) {
	if (!gameLoaded || !rendererStarted) {
		return false;
	}
	size_t size = api.serialize_size();
	if (size == 0) {
		return false;
	}
	out.resize(size);
	if (!api.serialize(out.data(), size)) {
		out.clear();
		return false;
	}
	return true;
}

bool Host::loadState(const std::vector<uint8_t>& data) {
	if (!gameLoaded || !rendererStarted || data.empty()) {
		return false;
	}
	return api.unserialize(data.data(), data.size());
}

} // namespace n3ds
