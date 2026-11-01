/*
 * 6.2 功能 4：RedefineClasses 执行。
 * - 必须用 jvmtiClassDefinition（规范 6），不能传 jclass[]。
 * - 只允许改方法体；错误码原样上报（6.4）。
 * - net.minecraft.* 在 C 侧二次拒绝（规范 17，Java 侧同样过滤）。
 * - RedefineClasses 同样触发 ClassFileLoadHook，所以必须用我方标记包住，否则会被保护段自己还原。
 */
#include "fj_globals.h"

#include <stdio.h>
#include <string.h>

JNIEXPORT jboolean JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeRedefineClass(JNIEnv *env, jclass cls,
                                                                              jstring internalName,
                                                                              jbyteArray newBytes)
{
    const char *name_chars;
    jclass target;
    jbyte *body = NULL;
    jsize body_len;
    jvmtiClassDefinition definition;
    jvmtiError err;
    int matches = 0;

    (void)cls;

    if (env == NULL || internalName == NULL || newBytes == NULL || g_jvmti == NULL) {
        return JNI_FALSE;
    }
    name_chars = (*env)->GetStringUTFChars(env, internalName, NULL);
    if (name_chars == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }

    /* 规范 17：Native 重定义禁止 net.minecraft.* */
    if (strncmp(name_chars, "net/minecraft/", 14) == 0) {
        fj_log("Redefine 拒绝: %s 属于 net.minecraft.*（规范 17，仅允许第三方类）", name_chars);
        (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
        return JNI_FALSE;
    }

    target = fj_find_loaded_class(env, name_chars, &matches);
    if (target == NULL) {
        fj_log("Redefine 失败: 找不到已加载类 %s", name_chars);
        (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
        return JNI_FALSE;
    }
    if (matches > 1) {
        fj_log("Redefine 警告: %s 有 %d 个同名已加载类，使用第一个", name_chars, matches);
    }

    body_len = (*env)->GetArrayLength(env, newBytes);
    body = (*env)->GetByteArrayElements(env, newBytes, NULL);
    if (body == NULL || body_len <= 0) {
        (*env)->ExceptionClear(env);
        if (body != NULL) {
            (*env)->ReleaseByteArrayElements(env, newBytes, body, JNI_ABORT);
        }
        fj_log("Redefine 失败: %s 的新字节为空", name_chars);
        (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
        return JNI_FALSE;
    }

    memset(&definition, 0, sizeof(definition));
    definition.klass = target;
    definition.class_byte_count = body_len;
    definition.class_bytes = (const unsigned char *)body;

    fj_selfmark_enter();
    err = (*g_jvmti)->RedefineClasses(g_jvmti, 1, &definition);
    fj_selfmark_exit();

    (*env)->ReleaseByteArrayElements(env, newBytes, body, JNI_ABORT);

    if (err == JVMTI_ERROR_NONE) {
        fj_log("Redefine 成功: %s (%d 字节)", name_chars, (int)body_len);
    } else {
        fj_log("Redefine 失败: %s (原因: %d %s)", name_chars, (int)err, fj_jvmti_error_name(err));
    }

    (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
    return (err == JVMTI_ERROR_NONE) ? JNI_TRUE : JNI_FALSE;
}
