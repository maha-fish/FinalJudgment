/*
 * 6.2 功能 6：类保护表 + 保护段两级判定（D10）。
 *
 * 快速路径（纯 C）：memcmp 当前字节与基线。一致 → 放行；不一致 → 进入慢速路径。零重入风险。
 * 慢速路径（Java）：NativeGuard.verifyAndRepair 做方法级指纹对比，区分"真篡改"与"JVM 规范化差异"。
 *   · 返回新字节 → 替换
 *   · 返回 null   → 放行
 *   · 失败 / 重入 → 与重入同一条降级路径：命中保护表返回基线
 * 重入检测用【线程本地】标记，不是全局标记。
 */
#include "fj_globals.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int fj_alloc_copy(jvmtiEnv *jvmti_env, const unsigned char *src, jint len,
                         unsigned char **out_bytes, jint *out_len)
{
    unsigned char *buffer = NULL;

    if (src == NULL || len <= 0 || jvmti_env == NULL) {
        return 0;
    }
    if ((*jvmti_env)->Allocate(jvmti_env, (jlong)len, &buffer) != JVMTI_ERROR_NONE || buffer == NULL) {
        return 0;
    }
    memcpy(buffer, src, (size_t)len);
    *out_bytes = buffer;
    *out_len = len;
    return 1;
}

/* 重入或回调失败时的统一降级：命中保护表 → 返回基线；未命中 → 放行 */
static int fj_protect_degrade(JNIEnv *jni_env, jvmtiEnv *jvmti_env, const char *name,
                              const unsigned char *baseline, jint baseline_len,
                              unsigned char **out_bytes, jint *out_len)
{
    (void)jni_env;
    (void)name;

    __sync_fetch_and_add(&g_protect_degraded, 1);
    if (fj_alloc_copy(jvmti_env, baseline, baseline_len, out_bytes, out_len)) {
        __sync_fetch_and_add(&g_protect_reverted, 1);
        return 1;
    }
    return 0;
}

int fj_register_protected(const char *internal_name, const jbyte *baseline, jint baseline_len)
{
    char *name_copy;
    unsigned char *baseline_copy;
    int i;

    if (internal_name == NULL || baseline == NULL || baseline_len <= 0) {
        return 0;
    }

    EnterCriticalSection(&g_lock);

    /* 同名 → 覆盖基线 */
    for (i = 0; i < g_protected_count; i++) {
        if (g_protected[i].internal_name != NULL
                && strcmp(g_protected[i].internal_name, internal_name) == 0) {
            baseline_copy = (unsigned char *)malloc((size_t)baseline_len);
            if (baseline_copy == NULL) {
                LeaveCriticalSection(&g_lock);
                return 0;
            }
            memcpy(baseline_copy, baseline, (size_t)baseline_len);
            free(g_protected[i].baseline);
            g_protected[i].baseline = baseline_copy;
            g_protected[i].baseline_len = baseline_len;
            LeaveCriticalSection(&g_lock);
            return 1;
        }
    }

    if (g_protected_count >= MAX_PROTECTED_CLASSES) {
        LeaveCriticalSection(&g_lock);
        return 0;
    }

    name_copy = fj_strdup(internal_name);
    baseline_copy = (unsigned char *)malloc((size_t)baseline_len);
    if (name_copy == NULL || baseline_copy == NULL) {
        free(name_copy);
        free(baseline_copy);
        LeaveCriticalSection(&g_lock);
        return 0;
    }
    memcpy(baseline_copy, baseline, (size_t)baseline_len);

    g_protected[g_protected_count].internal_name = name_copy;
    g_protected[g_protected_count].baseline = baseline_copy;
    g_protected[g_protected_count].baseline_len = baseline_len;
    g_protected_count++;
    g_protect_enabled = 1;

    LeaveCriticalSection(&g_lock);
    return 1;
}

/* 返回 1 表示需要把 out_bytes/out_len 交还 VM（替换），0 表示放行 */
int fj_protect_decide(JNIEnv *jni_env, jvmtiEnv *jvmti_env, const char *name,
                      const unsigned char *current, jint current_len,
                      unsigned char **out_bytes, jint *out_len)
{
    const unsigned char *baseline = NULL;
    jint baseline_len = 0;
    jstring jname = NULL;
    jbyteArray jcurrent = NULL;
    jobject returned = NULL;
    int failed = 0;
    int i;

    *out_bytes = NULL;
    *out_len = 0;

    if (name == NULL || current == NULL || current_len <= 0) {
        return 0;
    }

    EnterCriticalSection(&g_lock);
    for (i = 0; i < g_protected_count; i++) {
        if (g_protected[i].internal_name != NULL && strcmp(g_protected[i].internal_name, name) == 0) {
            baseline = g_protected[i].baseline;
            baseline_len = g_protected[i].baseline_len;
            break;
        }
    }
    LeaveCriticalSection(&g_lock);

    if (baseline == NULL) {
        return 0;   /* 保护表未命中 → 放行 */
    }

    /* ---------- 快速路径：纯 C，不调 Java ---------- */
    if (baseline_len == current_len && memcmp(baseline, current, (size_t)current_len) == 0) {
        __sync_fetch_and_add(&g_protect_fast_pass, 1);
        return 0;
    }

    /* ---------- 慢速路径前置：重入 / 无 guard / 无 JNIEnv → 降级 ---------- */
    if (FJ_IN_JAVA_CALLBACK() || g_guardClass == NULL || g_guardMethod == NULL || jni_env == NULL) {
        return fj_protect_degrade(jni_env, jvmti_env, name, baseline, baseline_len, out_bytes, out_len);
    }

    jname = (*jni_env)->NewStringUTF(jni_env, name);
    jcurrent = (*jni_env)->NewByteArray(jni_env, current_len);
    if (jname == NULL || jcurrent == NULL) {
        failed = 1;
    } else {
        (*jni_env)->SetByteArrayRegion(jni_env, jcurrent, 0, current_len, (const jbyte *)current);
    }

    if (!failed) {
        fj_reentrancy_enter();
        returned = (*jni_env)->CallStaticObjectMethod(jni_env, g_guardClass, g_guardMethod, jname, jcurrent);
        if ((*jni_env)->ExceptionCheck(jni_env)) {
            (*jni_env)->ExceptionClear(jni_env);
            failed = 1;
        }
        fj_reentrancy_exit();
    }

    if (jname != NULL) {
        (*jni_env)->DeleteLocalRef(jni_env, jname);
    }
    if (jcurrent != NULL) {
        (*jni_env)->DeleteLocalRef(jni_env, jcurrent);
    }

    if (failed) {
        if (returned != NULL) {
            (*jni_env)->DeleteLocalRef(jni_env, returned);
        }
        return fj_protect_degrade(jni_env, jvmti_env, name, baseline, baseline_len, out_bytes, out_len);
    }

    if (returned == NULL) {
        /* Java 判定「非篡改（JVM 规范化差异）」→ 放行 */
        __sync_fetch_and_add(&g_protect_slow_pass, 1);
        return 0;
    }

    {
        jsize length = (*jni_env)->GetArrayLength(jni_env, (jbyteArray)returned);
        jbyte *body = (*jni_env)->GetByteArrayElements(jni_env, (jbyteArray)returned, NULL);
        int ok = 0;

        if (body != NULL && length > 0) {
            ok = fj_alloc_copy(jvmti_env, (const unsigned char *)body, (jint)length, out_bytes, out_len);
            (*jni_env)->ReleaseByteArrayElements(jni_env, (jbyteArray)returned, body, JNI_ABORT);
        }
        (*jni_env)->DeleteLocalRef(jni_env, returned);

        if (ok) {
            __sync_fetch_and_add(&g_protect_slow_pass, 1);
            return 1;
        }
        return fj_protect_degrade(jni_env, jvmti_env, name, baseline, baseline_len, out_bytes, out_len);
    }
}

/* 注册守卫回调：签名严格 (Ljava/lang/String;[B)[B（自检 10） */
JNIEXPORT void JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeInitGuard(JNIEnv *env, jclass cls,
                                                                           jclass guard)
{
    (void)cls;

    if (env == NULL) {
        return;
    }
    if (g_guardClass != NULL) {
        (*env)->DeleteGlobalRef(env, g_guardClass);
        g_guardClass = NULL;
    }
    g_guardMethod = NULL;

    if (guard == NULL) {
        fj_log("守卫回调注册失败: 传入 null");
        return;
    }

    g_guardClass = (jclass)(*env)->NewGlobalRef(env, guard);
    if (g_guardClass == NULL) {
        (*env)->ExceptionClear(env);
        fj_log("守卫回调注册失败: NewGlobalRef 失败");
        return;
    }

    g_guardMethod = (*env)->GetStaticMethodID(env, g_guardClass, "verifyAndRepair",
                                              "(Ljava/lang/String;[B)[B");
    if (g_guardMethod == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, g_guardClass);
        g_guardClass = NULL;
        fj_log("守卫回调注册失败: 需要 public static byte[] verifyAndRepair(String, byte[])");
        return;
    }

    fj_log("守卫回调已挂载: verifyAndRepair (Ljava/lang/String;[B)[B");
}

JNIEXPORT jboolean JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeRegisterProtectedClass(JNIEnv *env, jclass cls,
                                                                                      jstring internalName,
                                                                                      jbyteArray baseline)
{
    const char *name_chars;
    jbyte *body = NULL;
    jsize body_len;
    int ok;

    (void)cls;

    if (env == NULL || internalName == NULL || baseline == NULL) {
        return JNI_FALSE;
    }
    name_chars = (*env)->GetStringUTFChars(env, internalName, NULL);
    if (name_chars == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }

    body_len = (*env)->GetArrayLength(env, baseline);
    body = (*env)->GetByteArrayElements(env, baseline, NULL);
    ok = (body != NULL && body_len > 0) ? fj_register_protected(name_chars, body, body_len) : 0;
    if (body != NULL) {
        (*env)->ReleaseByteArrayElements(env, baseline, body, JNI_ABORT);
    }

    if (ok) {
        fj_log("类保护注册成功: %s (%d 字节, 当前 %d/%d)",
               name_chars, (int)body_len, (int)g_protected_count, MAX_PROTECTED_CLASSES);
    } else if (g_protected_count >= MAX_PROTECTED_CLASSES) {
        fj_log("类保护注册失败: %s 超出上限 %d", name_chars, MAX_PROTECTED_CLASSES);
    } else {
        fj_log("类保护注册失败: %s (参数无效)", name_chars);
    }

    (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}
