/*
 * JNI glue between Kotlin (com.joseluixrax.gbaemu.emulator.NativeBridge) and
 * emu_core. Kept deliberately dumb: every function forwards to emu_core.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "emu_core.h"

#define JNI_FN(name) Java_com_joseluixrax_gbaemu_emulator_NativeBridge_##name

static char* _dupString(JNIEnv* env, jstring str) {
	if (!str) {
		return NULL;
	}
	const char* utf = (*env)->GetStringUTFChars(env, str, NULL);
	if (!utf) {
		return NULL;
	}
	char* copy = strdup(utf);
	(*env)->ReleaseStringUTFChars(env, str, utf);
	return copy;
}

JNIEXPORT jboolean JNICALL JNI_FN(loadRom)(JNIEnv* env, jobject thiz, jbyteArray rom, jstring savePath, jstring biosPath,
                                         jint sampleRate, jboolean skipBios, jint frameskip, jboolean idleLoopDetect,
                                         jboolean gbColorize) {
	jsize size = (*env)->GetArrayLength(env, rom);
	jbyte* data = (*env)->GetByteArrayElements(env, rom, NULL);
	if (!data) {
		return JNI_FALSE;
	}
	char* save = _dupString(env, savePath);
	char* bios = _dupString(env, biosPath);

	struct EmuOptions opts;
	emu_default_options(&opts);
	opts.sampleRate = (unsigned) sampleRate;
	opts.skipBios = skipBios;
	opts.frameskip = frameskip;
	opts.idleLoopDetect = idleLoopDetect;
	opts.gbColorize = gbColorize;

	bool ok = emu_load(data, (size_t) size, save, bios, &opts);

	(*env)->ReleaseByteArrayElements(env, rom, data, JNI_ABORT);
	free(save);
	free(bios);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(unload)(JNIEnv* env, jobject thiz) {
	emu_unload();
}

JNIEXPORT jint JNICALL JNI_FN(platform)(JNIEnv* env, jobject thiz) {
	return emu_platform();
}

JNIEXPORT void JNICALL JNI_FN(reset)(JNIEnv* env, jobject thiz) {
	emu_reset();
}

JNIEXPORT void JNICALL JNI_FN(runFrame)(JNIEnv* env, jobject thiz) {
	emu_run_frame();
}

JNIEXPORT void JNICALL JNI_FN(setKeys)(JNIEnv* env, jobject thiz, jint keys) {
	emu_set_keys((uint32_t) keys);
}

/* Copies the frame into a direct ByteBuffer. Returns (width << 16) | height. */
JNIEXPORT jint JNICALL JNI_FN(copyFrame)(JNIEnv* env, jobject thiz, jobject buffer) {
	void* dst = (*env)->GetDirectBufferAddress(env, buffer);
	jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
	if (!dst || capacity <= 0) {
		return 0;
	}
	unsigned width = 0, height = 0;
	size_t written = emu_copy_video(dst, (size_t) capacity, &width, &height);
	if (!written) {
		return 0;
	}
	return (jint) ((width << 16) | height);
}

/* Fills an interleaved stereo short[]; returns the number of frames. */
JNIEXPORT jint JNICALL JNI_FN(readAudio)(JNIEnv* env, jobject thiz, jshortArray buffer) {
	jsize length = (*env)->GetArrayLength(env, buffer);
	jshort* samples = (*env)->GetPrimitiveArrayCritical(env, buffer, NULL);
	if (!samples) {
		return 0;
	}
	size_t frames = emu_read_audio(samples, (size_t) length / 2);
	(*env)->ReleasePrimitiveArrayCritical(env, buffer, samples, 0);
	return (jint) frames;
}

JNIEXPORT void JNICALL JNI_FN(discardAudio)(JNIEnv* env, jobject thiz) {
	emu_discard_audio();
}

JNIEXPORT jboolean JNICALL JNI_FN(saveState)(JNIEnv* env, jobject thiz, jstring path) {
	char* p = _dupString(env, path);
	bool ok = emu_save_state(p);
	free(p);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(loadState)(JNIEnv* env, jobject thiz, jstring path) {
	char* p = _dupString(env, path);
	bool ok = emu_load_state(p);
	free(p);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(cheatsClear)(JNIEnv* env, jobject thiz) {
	emu_cheats_clear();
}

JNIEXPORT jboolean JNICALL JNI_FN(cheatsAdd)(JNIEnv* env, jobject thiz, jstring name, jstring code, jint type,
                                           jboolean enabled) {
	char* n = _dupString(env, name);
	char* c = _dupString(env, code);
	bool ok = emu_cheats_add(n, c, type, enabled);
	free(n);
	free(c);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(setRewind)(JNIEnv* env, jobject thiz, jboolean enabled, jint capacity, jint interval) {
	emu_set_rewind(enabled, capacity, interval);
}

JNIEXPORT jboolean JNICALL JNI_FN(rewindStep)(JNIEnv* env, jobject thiz) {
	return emu_rewind_step() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(setTilt)(JNIEnv* env, jobject thiz, jfloat x, jfloat y) {
	emu_set_tilt(x, y);
}

JNIEXPORT void JNICALL JNI_FN(setGyro)(JNIEnv* env, jobject thiz, jfloat z) {
	emu_set_gyro(z);
}

JNIEXPORT void JNICALL JNI_FN(setSolarLevel)(JNIEnv* env, jobject thiz, jint level) {
	emu_set_solar_level(level);
}

JNIEXPORT jboolean JNICALL JNI_FN(pollRumble)(JNIEnv* env, jobject thiz) {
	return emu_poll_rumble() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(setFrameskip)(JNIEnv* env, jobject thiz, jint frameskip) {
	emu_set_frameskip(frameskip);
}

JNIEXPORT jint JNICALL JNI_FN(hardware)(JNIEnv* env, jobject thiz) {
	return emu_hardware();
}

JNIEXPORT jstring JNICALL JNI_FN(gameTitle)(JNIEnv* env, jobject thiz) {
	char title[32];
	emu_game_title(title, sizeof(title));
	return (*env)->NewStringUTF(env, title);
}

JNIEXPORT jstring JNICALL JNI_FN(gameCode)(JNIEnv* env, jobject thiz) {
	char code[32];
	emu_game_code(code, sizeof(code));
	return (*env)->NewStringUTF(env, code);
}
