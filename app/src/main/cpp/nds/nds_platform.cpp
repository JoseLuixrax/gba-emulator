// melonDS asks the frontend for files, threads, locks and a few callbacks
// through melonDS::Platform. This is the Android (and desktop test) version.
#include "Platform.h"
#include "SPI_Firmware.h"

#include "nds_mic.h"
#include "nds_session.h"

#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace melonDS::Platform {

void SignalStop(StopReason reason, void* userdata) {
	Log(LogLevel::Info, "melonDS stopped (reason %d)\n", (int) reason);
}

// ------------------------------------------------------------------ files

static const char* modeString(FileMode mode, bool exists) {
	bool text = mode & FileMode::Text;
	if (mode & FileMode::Append) {
		return (mode & FileMode::Read) ? (text ? "a+" : "a+b") : (text ? "a" : "ab");
	}
	if (!(mode & FileMode::Write)) {
		return text ? "r" : "rb";
	}
	bool keep = (mode & FileMode::NoCreate) || ((mode & FileMode::Preserve) && exists);
	if (keep) {
		return text ? "r+" : "r+b";
	}
	if (mode & FileMode::Read) {
		return text ? "w+" : "w+b";
	}
	return text ? "w" : "wb";
}

std::string GetLocalFilePath(const std::string& filename) {
	if (!filename.empty() && filename[0] == '/') {
		return filename;
	}
	std::string dir = gNdsSession ? gNdsSession->dataDir : std::string(".");
	return dir + "/" + filename;
}

FileHandle* OpenFile(const std::string& path, FileMode mode) {
	if ((mode & (FileMode::ReadWrite | FileMode::Append)) == FileMode::None) {
		return nullptr;
	}
	bool exists = FileExists(path);
	if ((mode & FileMode::NoCreate) && !exists) {
		return nullptr;
	}
	FILE* file = fopen(path.c_str(), modeString(mode, exists));
	return reinterpret_cast<FileHandle*>(file);
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode) {
	return OpenFile(GetLocalFilePath(path), mode);
}

bool FileExists(const std::string& name) {
	struct stat st;
	return stat(name.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool LocalFileExists(const std::string& name) {
	return FileExists(GetLocalFilePath(name));
}

bool CheckFileWritable(const std::string& filepath) {
	FileHandle* file = OpenFile(filepath, FileMode::Append);
	if (!file) {
		return false;
	}
	CloseFile(file);
	return true;
}

bool CheckLocalFileWritable(const std::string& filepath) {
	return CheckFileWritable(GetLocalFilePath(filepath));
}

bool CloseFile(FileHandle* file) {
	return fclose(reinterpret_cast<FILE*>(file)) == 0;
}

bool IsEndOfFile(FileHandle* file) {
	return feof(reinterpret_cast<FILE*>(file)) != 0;
}

bool FileReadLine(char* str, int count, FileHandle* file) {
	return fgets(str, count, reinterpret_cast<FILE*>(file)) != nullptr;
}

u64 FilePosition(FileHandle* file) {
	return (u64) ftello(reinterpret_cast<FILE*>(file));
}

bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin) {
	int whence = SEEK_SET;
	if (origin == FileSeekOrigin::Current) {
		whence = SEEK_CUR;
	} else if (origin == FileSeekOrigin::End) {
		whence = SEEK_END;
	}
	return fseeko(reinterpret_cast<FILE*>(file), (off_t) offset, whence) == 0;
}

void FileRewind(FileHandle* file) {
	rewind(reinterpret_cast<FILE*>(file));
}

u64 FileRead(void* data, u64 size, u64 count, FileHandle* file) {
	return fread(data, size, count, reinterpret_cast<FILE*>(file));
}

bool FileFlush(FileHandle* file) {
	return fflush(reinterpret_cast<FILE*>(file)) == 0;
}

u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file) {
	return fwrite(data, size, count, reinterpret_cast<FILE*>(file));
}

u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...) {
	va_list args;
	va_start(args, fmt);
	int written = vfprintf(reinterpret_cast<FILE*>(file), fmt, args);
	va_end(args);
	return written < 0 ? 0 : (u64) written;
}

u64 FileLength(FileHandle* file) {
	FILE* f = reinterpret_cast<FILE*>(file);
	off_t pos = ftello(f);
	fseeko(f, 0, SEEK_END);
	off_t len = ftello(f);
	fseeko(f, pos, SEEK_SET);
	return (u64) len;
}

// -------------------------------------------------------------------- log

void Log(LogLevel level, const char* fmt, ...) {
	if (level == LogLevel::Debug) {
		return;
	}
	va_list args;
	va_start(args, fmt);
#ifdef __ANDROID__
	int prio = level == LogLevel::Error ? ANDROID_LOG_ERROR : (level == LogLevel::Warn ? ANDROID_LOG_WARN : ANDROID_LOG_INFO);
	__android_log_vprint(prio, "melonDS", fmt, args);
#else
	if (level != LogLevel::Info) {
		vfprintf(stderr, fmt, args);
	}
#endif
	va_end(args);
}

// ------------------------------------------------------- threads & locks

struct Thread {
	std::thread thread;
};

Thread* Thread_Create(std::function<void()> func) {
	return new Thread{std::thread(std::move(func))};
}

void Thread_Free(Thread* thread) {
	if (thread->thread.joinable()) {
		thread->thread.detach();
	}
	delete thread;
}

void Thread_Wait(Thread* thread) {
	if (thread->thread.joinable()) {
		thread->thread.join();
	}
}

struct Semaphore {
	std::mutex lock;
	std::condition_variable cond;
	int count = 0;
};

Semaphore* Semaphore_Create() {
	return new Semaphore();
}

void Semaphore_Free(Semaphore* sema) {
	delete sema;
}

void Semaphore_Reset(Semaphore* sema) {
	std::lock_guard<std::mutex> guard(sema->lock);
	sema->count = 0;
}

void Semaphore_Wait(Semaphore* sema) {
	std::unique_lock<std::mutex> guard(sema->lock);
	sema->cond.wait(guard, [sema] { return sema->count > 0; });
	--sema->count;
}

bool Semaphore_TryWait(Semaphore* sema, int timeout_ms) {
	std::unique_lock<std::mutex> guard(sema->lock);
	if (timeout_ms <= 0) {
		if (sema->count <= 0) {
			return false;
		}
	} else if (!sema->cond.wait_for(guard, std::chrono::milliseconds(timeout_ms), [sema] { return sema->count > 0; })) {
		return false;
	}
	--sema->count;
	return true;
}

void Semaphore_Post(Semaphore* sema, int count) {
	{
		std::lock_guard<std::mutex> guard(sema->lock);
		sema->count += count;
	}
	sema->cond.notify_all();
}

struct Mutex {
	std::mutex lock;
};

Mutex* Mutex_Create() {
	return new Mutex();
}

void Mutex_Free(Mutex* mutex) {
	delete mutex;
}

void Mutex_Lock(Mutex* mutex) {
	mutex->lock.lock();
}

void Mutex_Unlock(Mutex* mutex) {
	mutex->lock.unlock();
}

bool Mutex_TryLock(Mutex* mutex) {
	return mutex->lock.try_lock();
}

void Sleep(u64 usecs) {
	std::this_thread::sleep_for(std::chrono::microseconds(usecs));
}

u64 GetMSCount() {
	using namespace std::chrono;
	return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

u64 GetUSCount() {
	using namespace std::chrono;
	return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

// ------------------------------------------------------------------ saves

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata) {
	NdsSession* session = static_cast<NdsSession*>(userdata);
	if (!session || !savedata) {
		return;
	}
	std::lock_guard<std::mutex> guard(session->saveLock);
	session->saveData.assign(savedata, savedata + savelen);
	session->saveDirty = true;
	session->framesSinceSaveChange = 0;
}

void WriteGBASave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata) {}

void WriteFirmware(const Firmware& firmware, u32 writeoffset, u32 writelen, void* userdata) {}

void WriteDateTime(int year, int month, int day, int hour, int minute, int second, void* userdata) {}

// ---------------------------------------------- local multiplayer / wifi

void MP_Begin(void* userdata) {}
void MP_End(void* userdata) {}
int MP_SendPacket(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_RecvPacket(u8* data, u64* timestamp, void* userdata) { return 0; }
int MP_SendCmd(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_SendReply(u8* data, int len, u64 timestamp, u16 aid, void* userdata) { return 0; }
int MP_SendAck(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_RecvHostPacket(u8* data, u64* timestamp, void* userdata) { return 0; }
u16 MP_RecvReplies(u8* data, u64 timestamp, u16 aidmask, void* userdata) { return 0; }

int Net_SendPacket(u8* data, int len, void* userdata) { return 0; }
int Net_RecvPacket(u8* data, void* userdata) { return 0; }

// ------------------------------------------------------ camera, mic, misc

void Camera_Start(int num, void* userdata) {}
void Camera_Stop(int num, void* userdata) {}
void Camera_CaptureFrame(int num, u32* frame, int width, int height, bool yuv, void* userdata) {}

// The game opened or closed the mic: the app records only in between.
void Mic_Start(void* userdata) {
	ndsMicSetWanted(true);
}

void Mic_Stop(void* userdata) {
	ndsMicSetWanted(false);
}

int Mic_ReadInput(s16* data, int maxlength, void* userdata) {
	NdsSession* session = static_cast<NdsSession*>(userdata);
	if (!session || !session->micBlow) {
		if (ndsMicLive()) {
			// Only what was recorded: with nothing new melonDS keeps the
			// last sample for a moment instead of hearing gaps of silence.
			return ndsMicRead(data, maxlength);
		}
		memset(data, 0, sizeof(s16) * maxlength);
		return maxlength;
	}
	// Blowing on the mic is loud broadband noise.
	uint32_t state = session->noiseState;
	for (int i = 0; i < maxlength; ++i) {
		state = state * 1664525u + 1013904223u;
		data[i] = (s16) (((int32_t) (state >> 16) - 0x8000) / 2);
	}
	session->noiseState = state;
	return maxlength;
}

// DSi only (AAC audio in the DSi menu); not supported.
AACDecoder* AAC_Init() { return nullptr; }
void AAC_DeInit(AACDecoder* dec) {}
bool AAC_Configure(AACDecoder* dec, int frequency, int channels) { return false; }
bool AAC_DecodeFrame(AACDecoder* dec, const void* input, int inputlen, void* output, int outputlen) { return false; }

bool Addon_KeyDown(KeyType type, void* userdata) { return false; }
void Addon_RumbleStart(u32 len, void* userdata) {}
void Addon_RumbleStop(void* userdata) {}
float Addon_MotionQuery(MotionQueryType type, void* userdata) { return 0.0f; }

DynamicLibrary* DynamicLibrary_Load(const char* lib) {
	return reinterpret_cast<DynamicLibrary*>(dlopen(lib, RTLD_NOW | RTLD_LOCAL));
}

void DynamicLibrary_Unload(DynamicLibrary* lib) {
	if (lib) {
		dlclose(lib);
	}
}

void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name) {
	return lib ? dlsym(lib, name) : nullptr;
}

} // namespace melonDS::Platform
