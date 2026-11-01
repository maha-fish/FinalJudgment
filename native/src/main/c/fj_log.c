/*
 * 6.2 功能 9：日志桥接。
 * 优先 JNI 回调 Java 侧 public static void nativeLog(String)（签名严格 (Ljava/lang/String;)V），
 * 失败回退 fprintf(stderr)。线程可能未 attach → 先 GetEnv，失败再 AttachCurrentThread，用完 Detach。
 */
#include "fj_globals.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

JNIEXPORT void JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeInitLogger(JNIEnv *env, jclass cls,
                                                                          jclass holder)
{
    (void)cls;

    if (env == NULL) {
        return;
    }
    if (g_loggerClass != NULL) {
        (*env)->DeleteGlobalRef(env, g_loggerClass);
        g_loggerClass = NULL;
    }
    g_loggerMethod = NULL;

    if (holder == NULL) {
        fprintf(stderr, "[FJ-Native] nativeInitLogger: holder 为 null，保持 stderr 回退\n");
        return;
    }

    g_loggerClass = (jclass)(*env)->NewGlobalRef(env, holder);
    if (g_loggerClass == NULL) {
        (*env)->ExceptionClear(env);
        fprintf(stderr, "[FJ-Native] nativeInitLogger: NewGlobalRef 失败\n");
        return;
    }

    /* 签名严格固定，不得改变 */
    g_loggerMethod = (*env)->GetStaticMethodID(env, g_loggerClass, "nativeLog", "(Ljava/lang/String;)V");
    if (g_loggerMethod == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, g_loggerClass);
        g_loggerClass = NULL;
        fprintf(stderr, "[FJ-Native] nativeInitLogger 失败: 找不到 public static void nativeLog(String)\n");
        return;
    }

    fj_log("日志桥接已挂载: nativeLog (Ljava/lang/String;)V");
}

void fj_log(const char *fmt, ...)
{
    char buffer[1024];
    JNIEnv *env = NULL;
    jstring message;
    va_list args;
    int need_detach = 0;

    if (fmt == NULL) {
        return;
    }
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    /* D10：线程已处于 Java 回调中（重入）时禁止再调 Java，直接 stderr */
    if (FJ_IN_JAVA_CALLBACK()) {
        fprintf(stderr, "[FJ-Native] %s\n", buffer);
        return;
    }

    if (g_jvm == NULL) {
        fprintf(stderr, "[FJ-Native] %s\n", buffer);
        return;
    }

    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL) != JNI_OK || env == NULL) {
            fprintf(stderr, "[FJ-Native] %s\n", buffer);
            return;
        }
        need_detach = 1;
    }

    if (g_loggerClass != NULL && g_loggerMethod != NULL) {
        message = (*env)->NewStringUTF(env, buffer);
        if (message != NULL) {
            (*env)->CallStaticVoidMethod(env, g_loggerClass, g_loggerMethod, message);
            (*env)->DeleteLocalRef(env, message);
        }
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            fprintf(stderr, "[FJ-Native] %s\n", buffer);
        }
    } else {
        fprintf(stderr, "[FJ-Native] %s\n", buffer);
    }

    if (need_detach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}
