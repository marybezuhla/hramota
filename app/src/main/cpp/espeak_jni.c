// The English voice's sounds: eSpeak NG (GPL-3.0-or-later, built from source, see /espeak/VERSION) turns text into
// IPA phonemes, the way the voice (Piper) was trained. Only phonemes are made here; no sound, no files but its data.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <espeak-ng/speak_lib.h>

static int ready = 0;
// The voice set last: set again only when another is asked for (setting one reloads its dictionary)
static char current[64] = "";

JNIEXPORT jint JNICALL Java_ua_hramota_speech_Espeak_nativeInit(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    if (ready) return 1;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return -1;
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, p, espeakINITIALIZE_DONT_EXIT);
    (*env)->ReleaseStringUTFChars(env, path, p);
    ready = rate > 0;
    return rate;
}

JNIEXPORT jstring JNICALL Java_ua_hramota_speech_Espeak_nativePhonemes(JNIEnv *env, jclass cls, jstring text, jstring voice) {
    (void) cls;
    if (!ready) return NULL;
    const char *v = (*env)->GetStringUTFChars(env, voice, NULL);
    if (v == NULL) return NULL;
    if (strncmp(current, v, sizeof(current)) != 0) {
        espeak_ERROR set = espeak_SetVoiceByName(v);
        if (set != EE_OK) { (*env)->ReleaseStringUTFChars(env, voice, v); current[0] = 0; return NULL; }
        strncpy(current, v, sizeof(current) - 1);
        current[sizeof(current) - 1] = 0;
    }
    (*env)->ReleaseStringUTFChars(env, voice, v);
    const char *t = (*env)->GetStringUTFChars(env, text, NULL);
    if (t == NULL) return NULL;
    size_t cap = 256, len = 0;
    char *out = malloc(cap);
    if (out == NULL) { (*env)->ReleaseStringUTFChars(env, text, t); return NULL; }
    out[0] = 0;
    const void *at = t;
    // A clause at a time; words come back separated by spaces
    while (at != NULL) {
        const char *ph = espeak_TextToPhonemes(&at, espeakCHARS_UTF8, espeakPHONEMES_IPA);
        if (ph == NULL) break;
        size_t n = strlen(ph);
        if (len + n + 2 > cap) {
            while (len + n + 2 > cap) cap *= 2;
            char *grown = realloc(out, cap);
            if (grown == NULL) { free(out); (*env)->ReleaseStringUTFChars(env, text, t); return NULL; }
            out = grown;
        }
        if (len > 0) out[len++] = ' ';
        memcpy(out + len, ph, n);
        len += n;
        out[len] = 0;
    }
    (*env)->ReleaseStringUTFChars(env, text, t);
    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
