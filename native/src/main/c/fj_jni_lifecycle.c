/*
 * 6.2 功能 0：JNI 生命周期 + 全局变量定义。
 * JNI_OnLoad 不清任何业务状态、不抛异常（规范 40）；JNI_OnUnload 释放全部全局引用与堆内存。
 */
#include "fj_globals.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

JavaVM *g_jvm = NULL;
jvmtiEnv *g_jvmti = NULL;

jclass g_loggerClass = NULL;
jmethodID g_loggerMethod = NULL;
jclass g_guardClass = NULL;
jmethodID g_guardMethod = NULL;

FjProtectedClass g_protected[MAX_PROTECTED_CLASSES];
volatile int g_protected_count = 0;
volatile int g_protect_enabled = 0;

unsigned char *g_capture_bytes = NULL;
jint g_capture_len = 0;
char *g_capture_target = NULL;
volatile int g_capture_enabled = 0;

unsigned char *g_hook_bytes = NULL;
jint g_hook_bytes_len = 0;
char *g_hook_target_class = NULL;

volatile int g_self_retransform_count = 0;

FjHealthEntry *g_health_head = NULL;
volatile int g_health_count = 0;

volatile int g_capabilities = 0;
volatile int g_bootstrap_injected = 0;
CRITICAL_SECTION g_lock;

volatile int g_hook_events_total = 0;
volatile int g_hook_events_self = 0;
volatile int g_hook_events_other = 0;

static int g_lock_ready = 0;

static void fj_free_owned_memory(void)
{
    int i;

    if (g_capture_bytes != NULL) {
        free(g_capture_bytes);
        g_capture_bytes = NULL;
    }
    g_capture_len = 0;
    if (g_capture_target != NULL) {
        free(g_capture_target);
        g_capture_target = NULL;
    }
    g_capture_enabled = 0;

    if (g_hook_bytes != NULL) {
        free(g_hook_bytes);
        g_hook_bytes = NULL;
    }
    g_hook_bytes_len = 0;
    if (g_hook_target_class != NULL) {
        free(g_hook_target_class);
        g_hook_target_class = NULL;
    }

    for (i = 0; i < MAX_PROTECTED_CLASSES; i++) {
        if (g_protected[i].internal_name != NULL) {
            free(g_protected[i].internal_name);
            g_protected[i].internal_name = NULL;
        }
        if (g_protected[i].baseline != NULL) {
            free(g_protected[i].baseline);
            g_protected[i].baseline = NULL;
        }
        g_protected[i].baseline_len = 0;
    }
    g_protected_count = 0;
    g_protect_enabled = 0;

    while (g_health_head != NULL) {
        FjHealthEntry *next = g_health_head->next;
        if (g_health_head->uuid != NULL) {
            free(g_health_head->uuid);
        }
        free(g_health_head);
        g_health_head = next;
    }
    g_health_count = 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;

    g_jvm = vm;
    InitializeCriticalSection(&g_lock);
    g_lock_ready = 1;

    /* 日志桥接尚未挂载，只能走 stderr（功能 9 的回退路径） */
    fprintf(stderr, "[FJ-Native] JNI_OnLoad: dll 已加载，开始初始化 JVMTI\n");

    /* 失败不抛异常、不阻塞 Java 层（规范 40）：只置 g_capabilities = 0 */
    if (!fj_jvmti_init(vm)) {
        fprintf(stderr, "[FJ-Native] JVMTI 初始化失败，Native 层降级（能力位=0）\n");
    }

    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;

    (void)reserved;

    if (vm != NULL && (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) == JNI_OK) {
        if (g_loggerClass != NULL) {
            (*env)->DeleteGlobalRef(env, g_loggerClass);
            g_loggerClass = NULL;
        }
        if (g_guardClass != NULL) {
            (*env)->DeleteGlobalRef(env, g_guardClass);
            g_guardClass = NULL;
        }
    }
    g_loggerMethod = NULL;
    g_guardMethod = NULL;

    fj_jvmti_shutdown();
    fj_free_owned_memory();

    if (g_lock_ready) {
        DeleteCriticalSection(&g_lock);
        g_lock_ready = 0;
    }

    fprintf(stderr, "[FJ-Native] JNI_OnUnload: 全局引用与堆内存已释放\n");
    g_jvm = NULL;
}

/* 7.3 功能 1：加载流程最后一步的探针 */
JNIEXPORT jint JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativePing(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return (jint)FJ_PING_MAGIC;
}

/* 取类内部名（斜杠分隔）：用 JVMTI GetClassSignature，避免在 JNI 里调用 Java 方法 */
int fj_class_internal_name(JNIEnv *env, jclass klass, char *buf, int buflen)
{
    char *signature = NULL;
    int len;
    int start;
    int end;
    int count;

    (void)env;
    if (g_jvmti == NULL || klass == NULL || buf == NULL || buflen < 2) {
        return 0;
    }
    if ((*g_jvmti)->GetClassSignature(g_jvmti, klass, &signature, NULL) != JVMTI_ERROR_NONE
            || signature == NULL) {
        return 0;
    }

    len = (int)strlen(signature);
    start = (signature[0] == 'L') ? 1 : 0;
    end = (len > start && signature[len - 1] == ';') ? len - 1 : len;
    count = end - start;
    if (count < 0) {
        count = 0;
    }
    if (count > buflen - 1) {
        count = buflen - 1;
    }
    memcpy(buf, signature + start, (size_t)count);
    buf[count] = '\0';

    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)signature);
    return 1;
}
