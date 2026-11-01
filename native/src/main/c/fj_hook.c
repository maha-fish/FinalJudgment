/*
 * 6.2 功能 3：ClassFileLoadHook 四段逻辑（捕获 / 保护 / 替换 / 放行）。
 *
 * 本回调内【不调用任何 Java】：只做纯 C 计数与字节处理。原因是回调发生在类加载过程中，
 * 调用 Java 可能触发新的类加载（重入 hook）。汇总日志由 nativeRetransformClasses 在
 * 普通 JNI 上下文里输出。
 */
#include "fj_globals.h"

#include <stdlib.h>
#include <string.h>

#define FJ_MAX_CAPTURE_BYTES (16 * 1024 * 1024)

/* 交还给 JVM 的新字节必须用 JVMTI Allocate，且由 VM 负责释放 */
static int fj_set_new_bytes(jvmtiEnv *jvmti_env, const unsigned char *src, jint len,
                            jint *out_len, unsigned char **out_data)
{
    unsigned char *buffer = NULL;

    if (src == NULL || len <= 0 || out_len == NULL || out_data == NULL) {
        return 0;
    }
    if ((*jvmti_env)->Allocate(jvmti_env, (jlong)len, &buffer) != JVMTI_ERROR_NONE || buffer == NULL) {
        return 0;
    }
    memcpy(buffer, src, (size_t)len);
    *out_len = len;
    *out_data = buffer;
    return 1;
}

static void fj_capture_store(jint class_data_len, const unsigned char *class_data)
{
    unsigned char *copy;

    if (class_data == NULL || class_data_len <= 0 || class_data_len > FJ_MAX_CAPTURE_BYTES) {
        return;
    }
    copy = (unsigned char *)malloc((size_t)class_data_len);
    if (copy == NULL) {
        return;
    }
    memcpy(copy, class_data, (size_t)class_data_len);

    EnterCriticalSection(&g_lock);
    if (g_capture_bytes != NULL) {
        free(g_capture_bytes);
    }
    g_capture_bytes = copy;
    g_capture_len = class_data_len;
    LeaveCriticalSection(&g_lock);
}

void JNICALL fj_class_file_load_hook(jvmtiEnv *jvmti_env, JNIEnv *jni_env,
                                     jclass class_being_redefined, jobject loader,
                                     const char *name, jobject protection_domain,
                                     jint class_data_len, const unsigned char *class_data,
                                     jint *new_class_data_len, unsigned char **new_class_data)
{
    (void)jni_env;
    (void)loader;
    (void)protection_domain;

    __sync_fetch_and_add(&g_hook_events_total, 1);
    if (class_being_redefined != NULL) {
        if (IS_SELF_RETRANSFORM()) {
            __sync_fetch_and_add(&g_hook_events_self, 1);
        } else {
            __sync_fetch_and_add(&g_hook_events_other, 1);
        }
    }

    /* ---------- 第 1 段：捕获（最高优先） ---------- */
    if (g_capture_enabled && name != NULL && g_capture_target != NULL
            && strcmp(name, g_capture_target) == 0) {
        fj_capture_store(class_data_len, class_data);
        return;
    }

    /* ---------- 第 2 段：保护（两级：C 快速路径 → Java 慢速路径，D10） ---------- */
    if (!IS_SELF_RETRANSFORM() && g_protect_enabled && name != NULL) {
        unsigned char *replacement = NULL;
        jint replacement_len = 0;

        if (fj_protect_decide(jni_env, jvmti_env, name, class_data, class_data_len,
                              &replacement, &replacement_len)) {
            *new_class_data_len = replacement_len;
            *new_class_data = replacement;
            return;
        }
    }

    /* ---------- 第 3 段：替换 ---------- */
    if (name != NULL && g_hook_target_class != NULL && g_hook_bytes != NULL
            && strcmp(name, g_hook_target_class) == 0) {
        if (fj_set_new_bytes(jvmti_env, g_hook_bytes, g_hook_bytes_len,
                             new_class_data_len, new_class_data)) {
            return;
        }
    }

    /* ---------- 第 4 段：放行（不设置 new_class_data） ---------- */
}
