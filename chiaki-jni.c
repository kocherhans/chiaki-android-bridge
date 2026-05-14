/**
 * chiaki-jni.c  —  Intent Controller PS Remote Play JNI bridge
 *
 * Controller-input-only (headless) build.  No video decoding, no audio.
 * Video/audio callbacks in ChiakiConnectInfo are left NULL intentionally.
 *
 * ── JNI entry points ──────────────────────────────────────────────────────────
 *
 *   PsRemotePlayOutput (com.platform.intentcontroller.output):
 *     nativeConnect     — open a chiaki session, return opaque handle (jlong)
 *     nativeSendInput   — push one ChiakiControllerState frame
 *     nativeDisconnect  — stop + free session
 *
 *   PsRemotePlayConfigActivity (com.platform.intentcontroller.ui.output):
 *     nativeRegister    — local PIN pairing; returns RegisterResult (jbyteArray pair)
 *
 * ── Field names verified against chiaki-ng @ 3036f3fb ─────────────────────────
 *
 *   ChiakiConnectInfo  (session.h):
 *     .host             const char *
 *     .ps5              bool
 *     .regist_key       char[CHIAKI_SESSION_AUTH_SIZE]   (16 bytes)
 *     .morning          uint8_t[CHIAKI_RPCRYPT_KEY_SIZE] (16 bytes)
 *     .video_profile    ChiakiVideoProfile  — zero-init → console picks defaults
 *
 *   Callbacks are set on ChiakiSession after chiaki_session_init(), not via info:
 *     chiaki_session_set_event_cb()        — required: handles quit/error/rumble
 *     chiaki_session_set_video_sample_cb() — MUST be non-NULL (use intent_video_sample_cb stub;
 *                                                  NULL stalls streaminfo handshake)
 *
 *   ChiakiRegistInfo   (regist.h):
 *     .target           ChiakiTarget  (CHIAKI_TARGET_PS5_1 or CHIAKI_TARGET_PS4_9)
 *     .host             const char *
 *     .pin              uint32_t
 *     .psn_account_id   uint64_t  — numeric PSN Account ID (required for local pairing)
 *     .psn_online_id    NULL      — text ID path not used here
 *
 *   ChiakiRegisteredHost (regist.h):
 *     .rp_regist_key    char[CHIAKI_SESSION_AUTH_SIZE]   → Kotlin registKey
 *     .rp_key           uint8_t[CHIAKI_RPCRYPT_KEY_SIZE] → Kotlin morning
 *
 *   Constants (common.h / rpcrypt.h):
 *     CHIAKI_SESSION_AUTH_SIZE   0x10  (16)
 *     CHIAKI_RPCRYPT_KEY_SIZE    0x10  (16)
 *
 * ── Connection security ────────────────────────────────────────────────────────
 *
 *   chiaki-ng's ctrl channel uses TLS 1.2 (OpenSSL) for the initial HTTP
 *   upgrade and subsequent control messages.  The stream channel (takion)
 *   uses AES-GCM keyed from the registration keys via rpcrypt / gkcrypt.
 *   No additional TLS wrapping is needed at this layer; the keys exchanged
 *   during pairing (rp_regist_key / rp_key) authenticate every session.
 *
 * ── Build layout ──────────────────────────────────────────────────────────────
 *
 *   app/src/main/cpp/
 *     chiaki-jni.c          ← this file
 *     utils.h               ← internal helpers (xor, hex dump, etc.)
 *     chiaki/
 *       include/chiaki/     ← chiaki-ng lib/include/chiaki/
 *       src/                ← chiaki-ng lib/src/
 *     android_openssl/      ← KDAB/android_openssl
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <pthread.h>
#include <android/log.h>

#include <chiaki/session.h>
#include <chiaki/controller.h>
#include <chiaki/regist.h>
#include <chiaki/log.h>

#define LOG_TAG "chiaki-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Both keys are 16 bytes in chiaki-ng.
#define INTENT_REGIST_KEY_SIZE CHIAKI_SESSION_AUTH_SIZE  // 0x10 = 16
#define INTENT_MORNING_SIZE    CHIAKI_RPCRYPT_KEY_SIZE   // 0x10 = 16

// =============================================================================
// Session wrapper
// =============================================================================

typedef struct {
    ChiakiSession session;
    ChiakiLog     log;
    volatile bool stopped;
} SnapSession;

// =============================================================================
// Log callback — routes chiaki log levels to Android logcat
// =============================================================================

static void intent_log_cb(ChiakiLogLevel level, const char *msg, void *user) {
    switch (level) {
        case CHIAKI_LOG_DEBUG:
        case CHIAKI_LOG_VERBOSE: LOGD("[chiaki] %s", msg); break;
        case CHIAKI_LOG_INFO:    LOGI("[chiaki] %s", msg); break;
        case CHIAKI_LOG_WARNING: LOGW("[chiaki] %s", msg); break;
        case CHIAKI_LOG_ERROR:   LOGE("[chiaki] %s", msg); break;
        default:                 LOGI("[chiaki] %s", msg); break;
    }
}

// =============================================================================
// Session event callback
// =============================================================================

static void intent_event_cb(ChiakiEvent *event, void *user) {
    SnapSession *snap = (SnapSession *)user;
    switch (event->type) {
        case CHIAKI_EVENT_CONNECTED:
            LOGI("intent_event_cb: session CONNECTED");
            break;
        case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
            LOGW("intent_event_cb: unexpected LOGIN_PIN_REQUEST during session");
            break;
        case CHIAKI_EVENT_RUMBLE:
            LOGD("intent_event_cb: RUMBLE left=%u right=%u",
                 (unsigned)event->rumble.left,
                 (unsigned)event->rumble.right);
            break;
        case CHIAKI_EVENT_QUIT:
            snap->stopped = true;
            if (event->quit.reason == CHIAKI_QUIT_REASON_STOPPED) {
                LOGI("intent_event_cb: session quit — stopped cleanly");
            } else {
                LOGE("intent_event_cb: session quit — reason=%d (%s)",
                     (int)event->quit.reason,
                     event->quit.reason_str ? event->quit.reason_str : "");
            }
            break;
        default:
            LOGD("intent_event_cb: unhandled event type=%d", (int)event->type);
            break;
    }
}


// =============================================================================
// Stub video callback — discards all frames
//
// chiaki-ng's stream state machine requires a non-NULL video sample callback
// to advance past the streaminfo handshake. Passing NULL causes the session to
// stall waiting for an acknowledgement that never arrives, producing:
//   "StreamConnection didn't receive streaminfo"
// This stub accepts and immediately discards every frame so the handshake
// completes and the input channel opens normally.
// =============================================================================

static bool intent_video_sample_cb(uint8_t *buf, size_t buf_size, int codec, bool is_keyframe, void *user) {
    (void)buf;
    (void)buf_size;
    (void)codec;
    (void)is_keyframe;
    (void)user;
    // Intentionally empty — headless build discards all video frames.
    // Must return true to signal to chiaki-ng that the frame was consumed,
    // otherwise the stream state machine stalls.
    return true;
}

// =============================================================================
// nativeConnect
// PsRemotePlayOutput.nativeConnect(host, registKey, morning, isPs5) → Long
// =============================================================================

JNIEXPORT jlong JNICALL
Java_com_platform_intentcontroller_output_PsRemotePlayOutput_nativeConnect(
        JNIEnv    *env,
        jobject    thiz,
        jstring    host,
        jbyteArray registKey,
        jbyteArray morning,
        jboolean   isPs5)
{
    LOGI("nativeConnect: entry (isPs5=%d)", (int)isPs5);

    // ── Validate array lengths ────────────────────────────────────────────────
    jsize rk_len = (*env)->GetArrayLength(env, registKey);
    jsize mn_len = (*env)->GetArrayLength(env, morning);

    if ((size_t)rk_len != INTENT_REGIST_KEY_SIZE) {
        LOGE("nativeConnect: registKey must be %d bytes, got %d",
             (int)INTENT_REGIST_KEY_SIZE, (int)rk_len);
        return 0;
    }
    if ((size_t)mn_len != INTENT_MORNING_SIZE) {
        LOGE("nativeConnect: morning must be %d bytes, got %d",
             (int)INTENT_MORNING_SIZE, (int)mn_len);
        return 0;
    }

    // ── Allocate session wrapper ───────────────────────────────────────────────
    SnapSession *snap = (SnapSession *)calloc(1, sizeof(SnapSession));
    if (!snap) {
        LOGE("nativeConnect: OOM allocating SnapSession");
        return 0;
    }
    snap->stopped = false;

    chiaki_log_init(&snap->log, CHIAKI_LOG_WARNING | CHIAKI_LOG_ERROR, intent_log_cb, NULL);

    // ── Build ChiakiConnectInfo ────────────────────────────────────────────────
    ChiakiConnectInfo info;
    memset(&info, 0, sizeof(info));

    const char *host_str = (*env)->GetStringUTFChars(env, host, NULL);
    info.host = host_str;
    info.ps5  = (bool)isPs5;

    (*env)->GetByteArrayRegion(env, registKey, 0, rk_len, (jbyte *)info.regist_key);
    (*env)->GetByteArrayRegion(env, morning,   0, mn_len, (jbyte *)info.morning);

    // Request the minimum resolution the PS5 Remote Play encoder supports.
    // 256x144 is below the PS5 AvCap minimum and causes InitResult:-5.
    // 640x360 @ 30fps is the lowest accepted profile.
    // Must be set before chiaki_session_init() copies the info struct.
    info.video_profile.width   = 640;
    info.video_profile.height  = 360;
    info.video_profile.max_fps = 30;

    // ── Initialise session ────────────────────────────────────────────────────
    ChiakiErrorCode err = chiaki_session_init(&snap->session, &info, &snap->log);
    (*env)->ReleaseStringUTFChars(env, host, host_str);

    if (err != CHIAKI_ERR_SUCCESS) {
        LOGE("nativeConnect: chiaki_session_init failed: %d", (int)err);
        free(snap);
        return 0;
    }

    chiaki_session_set_event_cb(&snap->session, intent_event_cb, snap);
    // Register the stub video callback so chiaki-ng's stream state machine can
    // complete the streaminfo handshake. NULL here stalls the session — the PS5
    // sends streaminfo and waits for the client to acknowledge it via the video
    // pipeline before it will accept controller input.
    chiaki_session_set_video_sample_cb(&snap->session, intent_video_sample_cb, NULL);

    // ── Start session thread ──────────────────────────────────────────────────
    err = chiaki_session_start(&snap->session);
    if (err != CHIAKI_ERR_SUCCESS) {
        LOGE("nativeConnect: chiaki_session_start failed: %d", (int)err);
        chiaki_session_fini(&snap->session);
        free(snap);
        return 0;
    }

    LOGI("nativeConnect: session started (ptr=%p)", (void *)snap);
    return (jlong)(uintptr_t)snap;
}

// =============================================================================
// nativeSendInput
// PsRemotePlayOutput.nativeSendInput(session, buttons, l2, r2,
//                                    leftX, leftY, rightX, rightY,
//                                    touchId, touchX, touchY)
// =============================================================================

JNIEXPORT void JNICALL
Java_com_platform_intentcontroller_output_PsRemotePlayOutput_nativeSendInput(
        JNIEnv  *env,
        jobject  thiz,
        jlong    session,
        jint     buttons,
        jint     l2,
        jint     r2,
        jshort   leftX,
        jshort   leftY,
        jshort   rightX,
        jshort   rightY,
        jint     touchId,
        jint     touchX,
        jint     touchY)
{
    if (!session) return;
    SnapSession *snap = (SnapSession *)(uintptr_t)session;
    if (snap->stopped) return;

    ChiakiControllerState state;
    chiaki_controller_state_set_idle(&state);

    state.buttons  = (uint32_t)buttons;
    state.l2_state = (uint8_t) l2;
    state.r2_state = (uint8_t) r2;
    state.left_x   = (int16_t) leftX;
    state.left_y   = (int16_t) leftY;
    state.right_x  = (int16_t) rightX;
    state.right_y  = (int16_t) rightY;

    if (touchId >= 0) {
        state.touches[0].id = (uint8_t)(touchId & 0x7F);
        state.touches[0].x  = (uint16_t)touchX;
        state.touches[0].y  = (uint16_t)touchY;
    }

    chiaki_session_set_controller_state(&snap->session, &state);
}

// =============================================================================
// nativeDisconnect
// PsRemotePlayOutput.nativeDisconnect(session)
// =============================================================================

JNIEXPORT void JNICALL
Java_com_platform_intentcontroller_output_PsRemotePlayOutput_nativeDisconnect(
        JNIEnv  *env,
        jobject  thiz,
        jlong    session)
{
    if (!session) return;
    SnapSession *snap = (SnapSession *)(uintptr_t)session;
    LOGI("nativeDisconnect: stopping session (ptr=%p)", (void *)snap);

    chiaki_session_stop(&snap->session);
    chiaki_session_join(&snap->session);
    chiaki_session_fini(&snap->session);
    free(snap);

    LOGI("nativeDisconnect: done");
}

// =============================================================================
// Registration / pairing
// =============================================================================

typedef struct {
    pthread_mutex_t      mutex;
    pthread_cond_t       cond;
    bool                 done;
    bool                 success;
    ChiakiRegisteredHost registered_host;
} RegistWaiter;

static void intent_regist_cb(ChiakiRegistEvent *event, void *user) {
    RegistWaiter *w = (RegistWaiter *)user;
    pthread_mutex_lock(&w->mutex);
    w->done = true;
    if (event->type == CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS) {
        w->success         = true;
        w->registered_host = *event->registered_host;
        LOGI("nativeRegister: pairing succeeded");
    } else {
        w->success = false;
        LOGE("nativeRegister: pairing failed (event type=%d)", (int)event->type);
    }
    pthread_cond_signal(&w->cond);
    pthread_mutex_unlock(&w->mutex);
}

/**
 * nativeRegister — local PIN pairing with PS5 or PS4.
 *
 * Maps to:
 *   Java_com_platform_intentcontroller_ui_output_PsRemotePlayConfigActivity_nativeRegister
 *
 * Called on Dispatchers.IO (never on the main thread).
 *
 * @param host      Console local IP address.
 * @param pin       8-digit PIN shown on the console pairing screen.
 * @param accountId Numeric PSN Account ID (uint64). Obtain from psn-account-id.kozuki.dev.
 *                  This is required even for local PIN pairing — the PS5/PS4 uses it
 *                  to validate the request and will return 0x80108b02 if it is zero/invalid.
 * @param isPs5     JNI_TRUE for PS5, JNI_FALSE for PS4.
 * @return          RegisterResult(registKey: ByteArray, morning: ByteArray) or null on failure.
 */
JNIEXPORT jobject JNICALL
Java_com_platform_intentcontroller_ui_output_PsRemotePlayConfigActivity_nativeRegister(
        JNIEnv  *env,
        jobject  thiz,
        jstring  host,
        jint     pin,
        jlong    accountId,
        jboolean isPs5)
{
    const char *host_str = (*env)->GetStringUTFChars(env, host, NULL);
    LOGI("nativeRegister: host=%s isPs5=%d accountId=%llu",
         host_str, (int)isPs5, (unsigned long long)accountId);

    ChiakiLog log;
    chiaki_log_init(&log, CHIAKI_LOG_ALL, intent_log_cb, NULL);

    // ── Initialise waiter ─────────────────────────────────────────────────────
    RegistWaiter waiter;
    memset(&waiter, 0, sizeof(waiter));
    pthread_mutex_init(&waiter.mutex, NULL);
    pthread_cond_init(&waiter.cond, NULL);

    // ── Build registration info ───────────────────────────────────────────────
    ChiakiRegistInfo info;
    memset(&info, 0, sizeof(info));
    info.host           = host_str;
    info.pin            = (uint32_t)pin;

    // Select the appropriate protocol target.
    // PS4 firmwares >= 8.0 use CHIAKI_TARGET_PS4_9; use that for all PS4s
    // since anything older is unlikely to be in active use.
    info.target         = isPs5 ? CHIAKI_TARGET_PS5_1 : CHIAKI_TARGET_PS4_9;

    // The numeric PSN Account ID is required for local PIN pairing on both
    // PS4 and PS5. Setting this to zero causes a 403 with reason 0x80108b02
    // (Invalid PSN ID). Obtain via psn-account-id.kozuki.dev.
uint64_t accId = (uint64_t)accountId;
memcpy(info.psn_account_id, &accId, sizeof(info.psn_account_id));
    info.psn_online_id  = NULL;  // text username path — not used

    // ── Start registration thread ─────────────────────────────────────────────
    ChiakiRegist regist;
    ChiakiErrorCode err = chiaki_regist_start(&regist, &log, &info,
                                               intent_regist_cb, &waiter);
    if (err != CHIAKI_ERR_SUCCESS) {
        LOGE("nativeRegister: chiaki_regist_start failed: %d", (int)err);
        (*env)->ReleaseStringUTFChars(env, host, host_str);
        pthread_mutex_destroy(&waiter.mutex);
        pthread_cond_destroy(&waiter.cond);
        return NULL;
    }

    // ── Wait for callback ─────────────────────────────────────────────────────
    pthread_mutex_lock(&waiter.mutex);
    while (!waiter.done) {
        pthread_cond_wait(&waiter.cond, &waiter.mutex);
    }
    pthread_mutex_unlock(&waiter.mutex);

    // ── Cleanup ───────────────────────────────────────────────────────────────
    chiaki_regist_stop(&regist);
    chiaki_regist_fini(&regist);
    (*env)->ReleaseStringUTFChars(env, host, host_str);
    pthread_mutex_destroy(&waiter.mutex);
    pthread_cond_destroy(&waiter.cond);

    if (!waiter.success) return NULL;

    // ── Build Kotlin RegisterResult ───────────────────────────────────────────
    jclass cls = (*env)->FindClass(env,
        "com/platform/intentcontroller/ui/output/PsRemotePlayConfigActivity$RegisterResult");
    if (!cls) {
        LOGE("nativeRegister: RegisterResult class not found");
        return NULL;
    }

    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "([B[B)V");
    if (!ctor) {
        LOGE("nativeRegister: RegisterResult constructor not found");
        return NULL;
    }

    // rp_regist_key → registKey (16 bytes)
    jbyteArray jRegistKey = (*env)->NewByteArray(env, (jsize)INTENT_REGIST_KEY_SIZE);
    (*env)->SetByteArrayRegion(env, jRegistKey, 0, (jsize)INTENT_REGIST_KEY_SIZE,
        (const jbyte *)waiter.registered_host.rp_regist_key);

    // rp_key → morning (16 bytes)
    jbyteArray jMorning = (*env)->NewByteArray(env, (jsize)INTENT_MORNING_SIZE);
    (*env)->SetByteArrayRegion(env, jMorning, 0, (jsize)INTENT_MORNING_SIZE,
        (const jbyte *)waiter.registered_host.rp_key);

    LOGI("nativeRegister: returning RegisterResult to Kotlin");
    return (*env)->NewObject(env, cls, ctor, jRegistKey, jMorning);
}