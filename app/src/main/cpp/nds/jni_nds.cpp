// JNI glue between Kotlin (com.joseluixrax.gbaemu.emulator.NdsBridge) and
// nds_core. Same idea as jni_bridge.c on the GBA side: forward and convert.
#include <jni.h>

#include <string>

#include "nds_core.h"

#define JNI_FN(name) Java_com_joseluixrax_gbaemu_emulator_NdsBridge_##name

static std::string toStdString(JNIEnv* env, jstring str) {
	if (!str) {
		return std::string();
	}
	const char* utf = env->GetStringUTFChars(str, nullptr);
	if (!utf) {
		return std::string();
	}
	std::string copy(utf);
	env->ReleaseStringUTFChars(str, utf);
	return copy;
}

extern "C" {

JNIEXPORT jboolean JNICALL JNI_FN(loadRom)(JNIEnv* env, jobject, jbyteArray rom, jstring savePath, jstring dataDir,
                                           jint sampleRate, jint language, jboolean useJit) {
	jsize size = env->GetArrayLength(rom);
	jbyte* data = env->GetByteArrayElements(rom, nullptr);
	if (!data) {
		return JNI_FALSE;
	}
	std::string save = toStdString(env, savePath);
	std::string dir = toStdString(env, dataDir);
	bool ok = nds_load(data, (size_t) size, save.empty() ? nullptr : save.c_str(), dir.empty() ? nullptr : dir.c_str(),
	                   (unsigned) sampleRate, language, useJit);
	env->ReleaseByteArrayElements(rom, data, JNI_ABORT);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(loadRomFd)(JNIEnv* env, jobject, jint fd, jstring savePath, jstring dataDir,
                                             jint sampleRate, jint language, jboolean useJit) {
	std::string save = toStdString(env, savePath);
	std::string dir = toStdString(env, dataDir);
	bool ok = nds_load_fd(fd, save.empty() ? nullptr : save.c_str(), dir.empty() ? nullptr : dir.c_str(),
	                      (unsigned) sampleRate, language, useJit);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(unload)(JNIEnv*, jobject) {
	nds_unload();
}

JNIEXPORT void JNICALL JNI_FN(reset)(JNIEnv*, jobject) {
	nds_reset();
}

JNIEXPORT void JNICALL JNI_FN(runFrame)(JNIEnv*, jobject) {
	nds_run_frame();
}

// keys: GBA bits + X/Y + the mic bit. touch: (x << 16) | y, or -1 for none.
JNIEXPORT void JNICALL JNI_FN(setInput)(JNIEnv*, jobject, jint keys, jint touch) {
	nds_set_keys((uint32_t) keys);
	nds_set_mic_blow((keys & NDS_KEY_MIC) != 0);
	if (touch >= 0) {
		nds_touch((touch >> 16) & 0xFFFF, touch & 0xFFFF, true);
	} else {
		nds_touch(0, 0, false);
	}
}

// Copies both screens into a direct ByteBuffer. Returns (width << 16) | height.
JNIEXPORT jint JNICALL JNI_FN(copyFrame)(JNIEnv* env, jobject, jobject buffer) {
	void* dst = env->GetDirectBufferAddress(buffer);
	jlong capacity = env->GetDirectBufferCapacity(buffer);
	if (!dst || capacity <= 0) {
		return 0;
	}
	unsigned width = 0;
	unsigned height = 0;
	if (!nds_copy_video(dst, (size_t) capacity, &width, &height)) {
		return 0;
	}
	return (jint) ((width << 16) | height);
}

JNIEXPORT jint JNICALL JNI_FN(readAudio)(JNIEnv* env, jobject, jshortArray buffer) {
	jsize length = env->GetArrayLength(buffer);
	auto* samples = static_cast<jshort*>(env->GetPrimitiveArrayCritical(buffer, nullptr));
	if (!samples) {
		return 0;
	}
	size_t frames = nds_read_audio(samples, (size_t) length / 2);
	env->ReleasePrimitiveArrayCritical(buffer, samples, 0);
	return (jint) frames;
}

JNIEXPORT void JNICALL JNI_FN(discardAudio)(JNIEnv*, jobject) {
	nds_discard_audio();
}

JNIEXPORT void JNICALL JNI_FN(setAudioSpeed)(JNIEnv*, jobject, jfloat speed) {
	nds_set_audio_speed(speed);
}

JNIEXPORT void JNICALL JNI_FN(flushSave)(JNIEnv*, jobject) {
	nds_flush_save();
}

JNIEXPORT jstring JNICALL JNI_FN(gameTitle)(JNIEnv* env, jobject) {
	char title[64];
	nds_game_title(title, sizeof(title));
	return env->NewStringUTF(title);
}

} // extern "C"
