/*
 * 6.2 功能 5：运行时字节码捕获。
 * 设标志 → RetransformClasses → hook 捕获段存字节 → 复位 → 返回 byte[]。
 * 不能用 GetBytecodes：它只读单个方法的字节码，读不到整类。
 */
#include "fj_globals.h"

#include <stdlib.h>
#include <string.h>

JNIEXPORT jbyteArray JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeCaptureRuntimeBytes(JNIEnv *env, jclass cls,
                                                                                    jstring internalName)
{
    const char *name_chars;
    jclass target;
    unsigned char *snapshot = NULL;
    jint snapshot_len = 0;
    jbyteArray result = NULL;
    jvmtiError err;
    int matches = 0;

    (void)cls;

    if (env == NULL || internalName == NULL || g_jvmti == NULL) {
        return NULL;
    }
    name_chars = (*env)->GetStringUTFChars(env, internalName, NULL);
    if (name_chars == NULL) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    target = fj_find_loaded_class(env, name_chars, &matches);
    if (target == NULL) {
        fj_log("捕获失败: 找不到已加载类 %s", name_chars);
        (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
        return NULL;
    }

    /* 布置捕获现场 */
    EnterCriticalSection(&g_lock);
    if (g_capture_bytes != NULL) {
        free(g_capture_bytes);
        g_capture_bytes = NULL;
    }
    g_capture_len = 0;
    if (g_capture_target != NULL) {
        free(g_capture_target);
    }
    g_capture_target = fj_strdup(name_chars);
    g_capture_enabled = (g_capture_target != NULL) ? 1 : 0;
    LeaveCriticalSection(&g_lock);

    if (!g_capture_enabled) {
        fj_log("捕获失败: 现场布置失败 %s", name_chars);
        (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
        return NULL;
    }

    fj_selfmark_enter();
    err = (*g_jvmti)->RetransformClasses(g_jvmti, 1, &target);
    fj_selfmark_exit();

    if (err != JVMTI_ERROR_NONE) {
        fj_log("捕获失败: %s 重转换失败 (原因: %d %s)", name_chars, (int)err, fj_jvmti_error_name(err));
    }

    /* 关现场并把字节搬出锁区 */
    EnterCriticalSection(&g_lock);
    g_capture_enabled = 0;
    if (g_capture_bytes != NULL && g_capture_len > 0) {
        snapshot_len = g_capture_len;
        snapshot = (unsigned char *)malloc((size_t)snapshot_len);
        if (snapshot != NULL) {
            memcpy(snapshot, g_capture_bytes, (size_t)snapshot_len);
        }
        free(g_capture_bytes);
        g_capture_bytes = NULL;
        g_capture_len = 0;
    }
    LeaveCriticalSection(&g_lock);

    if (snapshot != NULL && snapshot_len > 0) {
        result = (*env)->NewByteArray(env, snapshot_len);
        if (result != NULL) {
            (*env)->SetByteArrayRegion(env, result, 0, snapshot_len, (const jbyte *)snapshot);
        }
        free(snapshot);
        fj_log("捕获成功: %s (%d 字节)", name_chars, (int)snapshot_len);
    } else {
        fj_log("捕获失败: %s 未收到字节（检查 hook 能力位）", name_chars);
    }

    (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
    return result;
}
