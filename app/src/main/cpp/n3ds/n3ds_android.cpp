// The 3DS game on Android. One thread owns the core, both EGL contexts and
// the audio stream; the Kotlin side (N3dsBridge) sends it work and input.
//
// Two contexts sharing textures: the core's (it keeps its own GL state, and
// asked for that with SET_HW_SHARED_CONTEXT) on a tiny pbuffer, and ours on
// the window, which draws the core's frame where the activity says.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <zlib.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "n3ds_gl.h"
#include "n3ds_host.h"
#include "n3ds_rom.h"
#include "n3ds_vfs.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "n3ds", __VA_ARGS__)
#define JNI_FN(name) Java_com_joseluixrax_gbaemu_emulator_N3dsBridge_##name

using Clock = std::chrono::steady_clock;

namespace {

const char STATE_MAGIC[8] = {'A', 'B', '3', 'D', 'S', 'S', 'T', '1'};

struct Session {
	std::thread thread;
	std::mutex lock;
	std::condition_variable wake;
	std::deque<std::function<void()>> tasks;
	bool quit = false;
	bool exited = false; // the thread is gone: nothing posted would ever run
	bool paused = true;

	std::atomic<int> viewX{0}, viewY{0}, viewW{0}, viewH{0};
	std::atomic<float> speed{1.0f};
	std::atomic<uint32_t> frameSize{0};
	std::atomic<float> fps{0.0f};

	// Buttons held, and turbo buttons (pressed for turboHalf frames, then
	// released as long, while held).
	std::atomic<uint32_t> buttons{0};
	std::atomic<uint32_t> turbo{0};
	std::atomic<int> turboHalf{2};
	std::atomic<float> volume{1.0f};
	uint64_t turboFrame = 0;

	// Render thread only.
	EGLDisplay display = EGL_NO_DISPLAY;
	EGLConfig config = nullptr;
	EGLContext presentContext = EGL_NO_CONTEXT;
	EGLContext coreContext = EGL_NO_CONTEXT;
	EGLSurface pbuffer = EGL_NO_SURFACE;
	EGLSurface window = EGL_NO_SURFACE;
	ANativeWindow* nativeWindow = nullptr;
	bool presenterReady = false;
	bool running = false; // game loaded and renderer started
	AAudioStream* audio = nullptr;
	bool audioStarted = false;
	int32_t audioRate = 0;          // the stream's; may differ from the core's
	Clock::time_point audioRetry;   // after a failed open, don't retry every frame
	double resamplePos = 0.0;
	int16_t lastLeft = 0, lastRight = 0;
	std::vector<int16_t> samples;
	std::vector<int16_t> resampled;
	Clock::time_point deadline;
	Clock::time_point fpsStart;
	int fpsFrames = 0;
};

Session* gSession = nullptr;

// ------------------------------------------------------------------ helpers

bool post(Session* s, std::function<void()> task) {
	{
		std::lock_guard<std::mutex> guard(s->lock);
		if (s->exited) {
			return false;
		}
		s->tasks.push_back(std::move(task));
	}
	s->wake.notify_all();
	return true;
}

// Waits for the task; T() if the thread has already gone.
template <typename T>
T runSync(Session* s, std::function<T()> task) {
	auto promise = std::make_shared<std::promise<T>>();
	auto future = promise->get_future();
	if (!post(s, [promise, task] { promise->set_value(task()); })) {
		return T();
	}
	return future.get();
}

void makeCoreCurrent(Session* s) {
	eglMakeCurrent(s->display, s->pbuffer, s->pbuffer, s->coreContext);
}

// --------------------------------------------------------------------- audio

void closeAudio(Session* s) {
	if (s->audio) {
		AAudioStream_requestStop(s->audio);
		AAudioStream_close(s->audio);
		s->audio = nullptr;
	}
	s->audioStarted = false;
}

AAudioStream* tryOpenAudio(int32_t rate) {
	AAudioStreamBuilder* builder = nullptr;
	if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
		return nullptr;
	}
	AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
	AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
	AAudioStreamBuilder_setChannelCount(builder, 2);
	if (rate > 0) {
		AAudioStreamBuilder_setSampleRate(builder, rate);
	}
	AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
	AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
	AAudioStream* stream = nullptr;
	if (AAudioStreamBuilder_openStream(builder, &stream) != AAUDIO_OK) {
		stream = nullptr;
	}
	AAudioStreamBuilder_delete(builder);
	return stream;
}

void openAudio(Session* s) {
	closeAudio(s);
	auto now = Clock::now();
	if (now < s->audioRetry) {
		return;
	}
	// The 3DS rate (32728 Hz), which Android normally converts itself; if
	// this phone won't, the phone's own rate and we convert (writeAudio).
	auto coreRate = (int32_t) n3ds::Host::get().sampleRate();
	AAudioStream* stream = tryOpenAudio(coreRate);
	if (!stream) {
		stream = tryOpenAudio(0);
	}
	if (!stream) {
		s->audioRetry = now + std::chrono::seconds(2);
		return;
	}
	s->audioRate = AAudioStream_getSampleRate(stream);
	if (s->audioRate <= 0) {
		s->audioRate = coreRate;
	}
	// About four frames of buffering: low latency without crackles.
	AAudioStream_setBufferSizeInFrames(stream, s->audioRate / 60 * 4);
	s->audio = stream;
}

// Linear interpolation from the core's rate to the stream's, carried over
// from one frame's chunk to the next.
void resample(Session* s, int32_t from, int32_t to) {
	const std::vector<int16_t>& in = s->samples;
	auto frames = (int64_t) (in.size() / 2);
	double step = (double) from / to;
	double pos = s->resamplePos; // -1 is the previous chunk's last frame
	s->resampled.clear();
	while (pos <= (double) (frames - 1)) {
		auto i = (int64_t) std::floor(pos);
		double f = pos - (double) i;
		int16_t aL = i < 0 ? s->lastLeft : in[2 * i];
		int16_t aR = i < 0 ? s->lastRight : in[2 * i + 1];
		int16_t bL = i + 1 < frames ? in[2 * (i + 1)] : aL;
		int16_t bR = i + 1 < frames ? in[2 * (i + 1) + 1] : aR;
		s->resampled.push_back((int16_t) (aL + (bL - aL) * f));
		s->resampled.push_back((int16_t) (aR + (bR - aR) * f));
		pos += step;
	}
	s->resamplePos = pos - (double) frames;
	s->lastLeft = in[2 * (frames - 1)];
	s->lastRight = in[2 * (frames - 1) + 1];
	s->samples.swap(s->resampled);
}

void startAudio(Session* s) {
	if (!s->audio) {
		openAudio(s);
	}
	if (s->audio && !s->audioStarted) {
		AAudioStream_requestStart(s->audio);
		s->audioStarted = true;
	}
}

void pauseAudio(Session* s) {
	if (s->audio && s->audioStarted) {
		AAudioStream_requestPause(s->audio);
		AAudioStream_requestFlush(s->audio);
		s->audioStarted = false;
	}
}

// Blocks until the stream took everything: this is what paces the game.
void writeAudio(Session* s) {
	if (s->samples.empty()) {
		return;
	}
	float volume = s->volume;
	if (volume < 0.999f) {
		for (int16_t& sample : s->samples) {
			sample = (int16_t) (sample * volume);
		}
	}
	if (!s->audio) {
		openAudio(s);
		startAudio(s);
	}
	if (!s->audio) {
		return;
	}
	auto coreRate = (int32_t) n3ds::Host::get().sampleRate();
	if (s->audioRate != coreRate && s->audioRate > 0) {
		resample(s, coreRate, s->audioRate);
		if (s->samples.empty()) {
			return;
		}
	}
	int32_t frames = (int32_t) (s->samples.size() / 2);
	aaudio_result_t result = AAudioStream_write(s->audio, s->samples.data(), frames, 200000000LL);
	if (result == AAUDIO_ERROR_DISCONNECTED) {
		// Headphones unplugged and the like: a fresh stream next time.
		closeAudio(s);
	}
}

// ----------------------------------------------------------------------- EGL

bool initDisplay(Session* s, std::string& error) {
	s->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if (s->display == EGL_NO_DISPLAY || !eglInitialize(s->display, nullptr, nullptr)) {
		error = "EGL";
		return false;
	}
	EGLint attribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
	                    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
	EGLint count = 0;
	if (!eglChooseConfig(s->display, attribs, &s->config, 1, &count) || count == 0) {
		error = "EGL config";
		return false;
	}
	eglBindAPI(EGL_OPENGL_ES_API);
	return true;
}

bool createContexts(Session* s, unsigned major, unsigned minor, std::string& error) {
	EGLint attribs[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, (EGLint) major, EGL_CONTEXT_MINOR_VERSION_KHR, (EGLint) minor, EGL_NONE};
	s->presentContext = eglCreateContext(s->display, s->config, EGL_NO_CONTEXT, attribs);
	if (s->presentContext == EGL_NO_CONTEXT) {
		error = "gles32";
		return false;
	}
	s->coreContext = eglCreateContext(s->display, s->config, s->presentContext, attribs);
	EGLint size[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
	s->pbuffer = eglCreatePbufferSurface(s->display, s->config, size);
	if (s->coreContext == EGL_NO_CONTEXT || s->pbuffer == EGL_NO_SURFACE) {
		error = "gles32";
		return false;
	}
	return true;
}

void releaseWindow(Session* s) {
	if (s->window != EGL_NO_SURFACE) {
		eglMakeCurrent(s->display, s->pbuffer, s->pbuffer, s->coreContext);
		eglDestroySurface(s->display, s->window);
		s->window = EGL_NO_SURFACE;
	}
	if (s->nativeWindow) {
		ANativeWindow_release(s->nativeWindow);
		s->nativeWindow = nullptr;
	}
}

void attachWindow(Session* s, ANativeWindow* window) {
	releaseWindow(s);
	if (!window) {
		return;
	}
	s->nativeWindow = window;
	s->window = eglCreateWindowSurface(s->display, s->config, window, nullptr);
	if (s->window == EGL_NO_SURFACE) {
		LOG("Cannot create the window surface (0x%x)", eglGetError());
		return;
	}
	eglMakeCurrent(s->display, s->window, s->window, s->presentContext);
	// The audio paces the game; don't also wait for the display's refresh.
	eglSwapInterval(s->display, 0);
	if (!s->presenterReady) {
		s->presenterReady = n3ds::glLoad() && n3ds::presenterInit();
	}
}

void destroyEgl(Session* s) {
	if (s->display == EGL_NO_DISPLAY) {
		return;
	}
	releaseWindow(s);
	eglMakeCurrent(s->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	if (s->pbuffer != EGL_NO_SURFACE) eglDestroySurface(s->display, s->pbuffer);
	if (s->coreContext != EGL_NO_CONTEXT) eglDestroyContext(s->display, s->coreContext);
	if (s->presentContext != EGL_NO_CONTEXT) eglDestroyContext(s->display, s->presentContext);
	eglTerminate(s->display);
	s->display = EGL_NO_DISPLAY;
}

// ------------------------------------------------------------------- frames

void step(Session* s) {
	n3ds::Host& host = n3ds::Host::get();
	float speed = s->speed;
	int frames = speed > 1.0f ? (int) speed : 1;
	bool withSound = speed == 1.0f;

	makeCoreCurrent(s);
	s->samples.clear();
	std::vector<int16_t> chunk;
	for (int i = 0; i < frames; ++i) {
		uint32_t held = s->buttons;
		uint32_t turbo = s->turbo;
		if (turbo == 0) {
			s->turboFrame = 0;
			host.setButtons(held);
		} else {
			bool down = (s->turboFrame++ / (uint64_t) std::max(s->turboHalf.load(), 1)) % 2 == 0;
			host.setButtons(down ? held | turbo : held);
		}
		host.runFrame();
		host.takeAudio(chunk);
		if (withSound) {
			s->samples.insert(s->samples.end(), chunk.begin(), chunk.end());
		}
	}
	void* fence = n3ds::fenceAfterFrame();
	s->frameSize = (host.frameWidth() << 16) | host.frameHeight();

	if (s->window != EGL_NO_SURFACE) {
		eglMakeCurrent(s->display, s->window, s->window, s->presentContext);
		n3ds::fenceWait(fence);
		EGLint width = 0, height = 0;
		eglQuerySurface(s->display, s->window, EGL_WIDTH, &width);
		eglQuerySurface(s->display, s->window, EGL_HEIGHT, &height);
		const n3ds::Target& target = host.target();
		n3ds::present(target.texture, target.width, target.height, host.frameWidth(), host.frameHeight(), s->viewX,
		              s->viewY, s->viewW, s->viewH, width, height);
		eglSwapBuffers(s->display, s->window);
	}

	if (withSound) {
		writeAudio(s);
	}
	// Without sound to wait on (fast forward, slow motion, games that are
	// silent for a while) keep time ourselves.
	auto now = Clock::now();
	double period = 1.0 / (host.fps() > 0 ? host.fps() : 60.0);
	if (speed < 1.0f) {
		period /= std::max(speed, 0.05f);
	}
	auto stepTime = std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(period));
	if (s->deadline < now - stepTime * 3) {
		s->deadline = now;
	}
	s->deadline += stepTime;
	if (s->deadline > now) {
		std::this_thread::sleep_until(s->deadline);
	}

	s->fpsFrames += frames;
	auto elapsed = std::chrono::duration<double>(Clock::now() - s->fpsStart).count();
	if (elapsed >= 1.0) {
		s->fps = (float) (s->fpsFrames / elapsed);
		s->fpsFrames = 0;
		s->fpsStart = Clock::now();
	}
}

void threadMain(Session* s) {
	while (true) {
		std::function<void()> task;
		bool run = false;
		{
			std::unique_lock<std::mutex> guard(s->lock);
			bool canRun = s->running && !s->paused && s->window != EGL_NO_SURFACE;
			if (s->tasks.empty() && !s->quit && !canRun) {
				s->wake.wait(guard);
			}
			if (!s->tasks.empty()) {
				task = std::move(s->tasks.front());
				s->tasks.pop_front();
			} else if (s->quit) {
				s->exited = true;
				break;
			} else {
				run = s->running && !s->paused && s->window != EGL_NO_SURFACE;
			}
		}
		if (task) {
			task();
		} else if (run) {
			step(s);
		}
	}
}

// ---------------------------------------------------------------- start/stop

std::string startOnThread(Session* s, const std::string& systemDir, const std::string& saveDir,
                          const std::string& gamePath, int fd, int64_t offset, int64_t size,
                          const std::vector<std::pair<std::string, std::string>>& options) {
	std::string error;
	n3ds::Host& host = n3ds::Host::get();
	if (!initDisplay(s, error)) {
		return error;
	}
	host.setDirectories(systemDir, saveDir);
	// Packed in the APK as libazahar_libretro.so; the linker finds it there.
	if (!host.open("libazahar_libretro.so", error)) {
		return error;
	}
	for (const auto& option : options) {
		host.setOption(option.first, option.second);
	}
	if (!n3dsVfsMap(gamePath, fd, (uint64_t) offset, (uint64_t) size)) {
		return "rom";
	}
	if (!host.loadGame(gamePath, error)) {
		return error;
	}
	unsigned major = host.hwVersionMajor() ? host.hwVersionMajor() : 3;
	unsigned minor = host.hwVersionMajor() ? host.hwVersionMinor() : 2;
	if (!createContexts(s, major, minor, error)) {
		return error;
	}
	makeCoreCurrent(s);
	if (!host.startRenderer(error)) {
		return error;
	}
	if (!host.lastMessage().empty()) {
		// The game itself failed to load inside context_reset.
		return host.lastMessage();
	}
	s->running = true;
	s->deadline = Clock::now();
	s->fpsStart = Clock::now();
	openAudio(s);
	return std::string();
}

void stopOnThread(Session* s) {
	n3ds::Host& host = n3ds::Host::get();
	closeAudio(s);
	if (s->display != EGL_NO_DISPLAY && s->coreContext != EGL_NO_CONTEXT) {
		makeCoreCurrent(s);
		host.stopRenderer();
	}
	host.unloadGame();
	host.close();
	n3dsVfsUnmapAll();
	if (s->presenterReady && s->window != EGL_NO_SURFACE) {
		eglMakeCurrent(s->display, s->window, s->window, s->presentContext);
		n3ds::presenterDestroy();
	}
	s->presenterReady = false;
	destroyEgl(s);
	s->running = false;
}

// ------------------------------------------------------------------- states

bool writeState(const std::string& path, const std::vector<uint8_t>& raw) {
	uLongf packed = compressBound(raw.size());
	std::vector<uint8_t> out(16 + packed);
	memcpy(out.data(), STATE_MAGIC, 8);
	uint64_t length = raw.size();
	for (int i = 0; i < 8; ++i) out[8 + i] = (uint8_t) (length >> (8 * i));
	if (compress2(out.data() + 16, &packed, raw.data(), raw.size(), Z_BEST_SPEED) != Z_OK) {
		return false;
	}
	out.resize(16 + packed);
	std::string tmp = path + ".tmp";
	FILE* file = fopen(tmp.c_str(), "wb");
	if (!file) return false;
	bool ok = fwrite(out.data(), 1, out.size(), file) == out.size();
	ok = fflush(file) == 0 && ok;
	fclose(file);
	if (!ok || rename(tmp.c_str(), path.c_str()) != 0) {
		remove(tmp.c_str());
		return false;
	}
	return true;
}

bool readState(const std::string& path, std::vector<uint8_t>& raw) {
	FILE* file = fopen(path.c_str(), "rb");
	if (!file) return false;
	std::vector<uint8_t> data;
	fseek(file, 0, SEEK_END);
	long size = ftell(file);
	fseek(file, 0, SEEK_SET);
	if (size > 16) {
		data.resize((size_t) size);
		if (fread(data.data(), 1, data.size(), file) != data.size()) data.clear();
	}
	fclose(file);
	if (data.size() <= 16 || memcmp(data.data(), STATE_MAGIC, 8) != 0) return false;
	uint64_t length = 0;
	for (int i = 0; i < 8; ++i) length |= (uint64_t) data[8 + i] << (8 * i);
	if (length == 0 || length > (1ull << 31)) return false;
	raw.resize((size_t) length);
	uLongf unpacked = (uLongf) length;
	return uncompress(raw.data(), &unpacked, data.data() + 16, data.size() - 16) == Z_OK && unpacked == length;
}

std::string toString(JNIEnv* env, jstring value) {
	if (!value) return std::string();
	const char* utf = env->GetStringUTFChars(value, nullptr);
	std::string out = utf ? utf : "";
	if (utf) env->ReleaseStringUTFChars(value, utf);
	return out;
}

} // namespace

extern "C" {

// Returns "" when the game is running, otherwise why it isn't.
JNIEXPORT jstring JNICALL JNI_FN(start)(JNIEnv* env, jobject, jstring systemDir, jstring saveDir, jstring gamePath,
                                         jint fd, jlong offset, jlong size, jobjectArray options) {
	if (gSession) {
		return env->NewStringUTF("busy");
	}
	std::vector<std::pair<std::string, std::string>> pairs;
	jsize count = options ? env->GetArrayLength(options) : 0;
	for (jsize i = 0; i + 1 < count; i += 2) {
		auto key = (jstring) env->GetObjectArrayElement(options, i);
		auto value = (jstring) env->GetObjectArrayElement(options, i + 1);
		pairs.emplace_back(toString(env, key), toString(env, value));
		env->DeleteLocalRef(key);
		env->DeleteLocalRef(value);
	}
	gSession = new Session();
	Session* s = gSession;
	s->thread = std::thread(threadMain, s);
	std::string system = toString(env, systemDir);
	std::string save = toString(env, saveDir);
	std::string game = toString(env, gamePath);
	std::string error = runSync<std::string>(s, [=] {
		return startOnThread(s, system, save, game, fd, offset, size, pairs);
	});
	return env->NewStringUTF(error.c_str());
}

JNIEXPORT void JNICALL JNI_FN(stop)(JNIEnv*, jobject) {
	Session* s = gSession;
	if (!s) return;
	runSync<bool>(s, [s] {
		stopOnThread(s);
		return true;
	});
	{
		std::lock_guard<std::mutex> guard(s->lock);
		s->quit = true;
	}
	s->wake.notify_all();
	s->thread.join();
	// Not deleted: a call from another thread may still hold the pointer, and
	// the process ends right after this anyway.
	gSession = nullptr;
}

// Must not return before the old surface is let go: Android may destroy it
// right after surfaceDestroyed().
JNIEXPORT void JNICALL JNI_FN(setSurface)(JNIEnv* env, jobject, jobject surface) {
	Session* s = gSession;
	if (!s) return;
	ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
	runSync<bool>(s, [s, window] {
		if (s->display == EGL_NO_DISPLAY) {
			if (window) ANativeWindow_release(window);
			return true;
		}
		attachWindow(s, window);
		return true;
	});
}

JNIEXPORT void JNICALL JNI_FN(setViewport)(JNIEnv*, jobject, jint x, jint y, jint w, jint h) {
	Session* s = gSession;
	if (!s) return;
	s->viewX = x;
	s->viewY = y;
	s->viewW = w;
	s->viewH = h;
}

JNIEXPORT void JNICALL JNI_FN(setPaused)(JNIEnv*, jobject, jboolean paused) {
	Session* s = gSession;
	if (!s) return;
	post(s, [s, paused] {
		s->paused = paused;
		if (paused) {
			pauseAudio(s);
		} else {
			s->deadline = Clock::now();
			startAudio(s);
		}
	});
}

JNIEXPORT void JNICALL JNI_FN(setSpeed)(JNIEnv*, jobject, jfloat speed) {
	if (gSession) gSession->speed = speed > 0.0f ? speed : 1.0f;
}

JNIEXPORT void JNICALL JNI_FN(setInput)(JNIEnv*, jobject, jint buttons, jint turbo, jint lx, jint ly, jint rx, jint ry,
                                         jint px, jint py, jboolean pressed) {
	n3ds::Host& host = n3ds::Host::get();
	if (gSession) {
		gSession->buttons = (uint32_t) buttons;
		gSession->turbo = (uint32_t) turbo;
	}
	host.setAnalog(0, (int16_t) lx, (int16_t) ly);
	host.setAnalog(1, (int16_t) rx, (int16_t) ry);
	host.setPointer((int16_t) px, (int16_t) py, pressed);
}

// 0..1; 0 still writes silence, so the audio keeps pacing the game.
JNIEXPORT void JNICALL JNI_FN(setVolume)(JNIEnv*, jobject, jfloat volume) {
	if (gSession) gSession->volume = std::min(std::max(volume, 0.0f), 1.0f);
}

JNIEXPORT void JNICALL JNI_FN(setTurboSpeed)(JNIEnv*, jobject, jint halfPeriod) {
	if (gSession) gSession->turboHalf = halfPeriod > 0 ? halfPeriod : 1;
}

JNIEXPORT jint JNICALL JNI_FN(frameSize)(JNIEnv*, jobject) {
	return gSession ? (jint) gSession->frameSize.load() : 0;
}

JNIEXPORT jfloat JNICALL JNI_FN(fps)(JNIEnv*, jobject) {
	return gSession ? gSession->fps.load() : 0.0f;
}

JNIEXPORT jboolean JNICALL JNI_FN(saveState)(JNIEnv* env, jobject, jstring path) {
	Session* s = gSession;
	if (!s) return JNI_FALSE;
	std::string target = toString(env, path);
	return runSync<bool>(s, [s, target] {
		if (!s->running) return false;
		makeCoreCurrent(s);
		std::vector<uint8_t> raw;
		return n3ds::Host::get().saveState(raw) && writeState(target, raw);
	}) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(loadState)(JNIEnv* env, jobject, jstring path) {
	Session* s = gSession;
	if (!s) return JNI_FALSE;
	std::string source = toString(env, path);
	return runSync<bool>(s, [s, source] {
		if (!s->running) return false;
		std::vector<uint8_t> raw;
		if (!readState(source, raw)) return false;
		makeCoreCurrent(s);
		return n3ds::Host::get().loadState(raw);
	}) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(reset)(JNIEnv*, jobject) {
	Session* s = gSession;
	if (!s) return;
	post(s, [s] {
		if (!s->running) return;
		makeCoreCurrent(s);
		n3ds::Host::get().reset();
	});
}

JNIEXPORT void JNICALL JNI_FN(setOption)(JNIEnv* env, jobject, jstring key, jstring value) {
	Session* s = gSession;
	if (!s) return;
	std::string k = toString(env, key);
	std::string v = toString(env, value);
	post(s, [k, v] { n3ds::Host::get().setOption(k, v); });
}

// [kind, problem, offset, size] for a ROM file (see n3ds_rom.h).
JNIEXPORT jlongArray JNICALL JNI_FN(inspectRom)(JNIEnv* env, jobject, jint fd, jlong fileSize) {
	N3dsRomInfo info = n3dsInspectRom(fd, (uint64_t) fileSize);
	jlong values[4] = {(jlong) info.kind, (jlong) info.problem, (jlong) info.offset, (jlong) info.size};
	jlongArray out = env->NewLongArray(4);
	env->SetLongArrayRegion(out, 0, 4, values);
	return out;
}

} // extern "C"
