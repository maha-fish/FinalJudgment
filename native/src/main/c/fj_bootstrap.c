/*
 * 6.2 功能 10：Bootstrap 注入（AddToBootstrapClassLoaderSearch）。
 * 一旦成功不可撤回：置 g_bootstrap_injected 后拒绝二次注入（幂等返回 true）。
 */
#include "fj_globals.h"

#include <stdio.h>

JNIEXPORT jboolean JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeInjectBootstrap(JNIEnv *env, jclass cls,
                                                                                jstring jarPath)
{
    const char *path_chars;
    jvmtiError err;

    (void)cls;

    if (g_bootstrap_injected) {
        fj_log("Bootstrap 注入跳过: 已注入（不可撤回）");
        return JNI_TRUE;
    }
    if (env == NULL || jarPath == NULL || g_jvmti == NULL) {
        fj_log("Bootstrap 注入失败: JVMTI 或参数不可用");
        return JNI_FALSE;
    }

    path_chars = (*env)->GetStringUTFChars(env, jarPath, NULL);
    if (path_chars == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }

    err = (*g_jvmti)->AddToBootstrapClassLoaderSearch(g_jvmti, path_chars);
    if (err == JVMTI_ERROR_NONE) {
        g_bootstrap_injected = 1;
        fj_log("Bootstrap 注入成功: %s", path_chars);
    } else {
        fj_log("Bootstrap 注入失败: %s (原因: %d %s)",
               path_chars, (int)err, fj_jvmti_error_name(err));
    }

    (*env)->ReleaseStringUTFChars(env, jarPath, path_chars);
    return (err == JVMTI_ERROR_NONE) ? JNI_TRUE : JNI_FALSE;
}
