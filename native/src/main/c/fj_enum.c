/*
 * 6.2 功能 11：类枚举 / 常量池。
 * 返回内部名（斜杠分隔），只收对象签名（跳过数组与原始类型）。
 */
#include "fj_globals.h"

#include <stdlib.h>
#include <string.h>

JNIEXPORT jobjectArray JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeEnumerateLoadedClasses(JNIEnv *env, jclass cls)
{
    jint total = 0;
    jclass *classes = NULL;
    char **names;
    jint count = 0;
    jobjectArray result = NULL;
    jclass string_class;
    jint i;

    (void)cls;

    if (env == NULL || g_jvmti == NULL) {
        return NULL;
    }
    if ((*g_jvmti)->GetLoadedClasses(g_jvmti, &total, &classes) != JVMTI_ERROR_NONE || classes == NULL) {
        return NULL;
    }

    names = (char **)calloc((size_t)(total > 0 ? total : 1), sizeof(char *));
    if (names == NULL) {
        (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);
        return NULL;
    }

    for (i = 0; i < total; i++) {
        char *signature = NULL;
        char internal[512];

        if ((*g_jvmti)->GetClassSignature(g_jvmti, classes[i], &signature, NULL) != JVMTI_ERROR_NONE
                || signature == NULL) {
            continue;
        }
        if (fj_signature_to_internal(signature, internal, (int)sizeof(internal))) {
            names[count] = fj_strdup(internal);
            if (names[count] != NULL) {
                count++;
            }
        }
        (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)signature);
    }
    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);

    string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class != NULL) {
        result = (*env)->NewObjectArray(env, count, string_class, NULL);
        if (result != NULL) {
            for (i = 0; i < count; i++) {
                jstring item = (*env)->NewStringUTF(env, names[i]);
                if (item != NULL) {
                    (*env)->SetObjectArrayElement(env, result, i, item);
                    (*env)->DeleteLocalRef(env, item);
                }
            }
        }
        (*env)->DeleteLocalRef(env, string_class);
    } else {
        (*env)->ExceptionClear(env);
    }

    for (i = 0; i < count; i++) {
        free(names[i]);
    }
    free(names);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeGetConstantPool(JNIEnv *env, jclass cls,
                                                                                jstring internalName)
{
    const char *name_chars;
    jclass target;
    jint constant_count = 0;
    jint length = 0;
    unsigned char *bytes = NULL;
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
        fj_log("常量池读取失败: 找不到已加载类 %s", name_chars);
        (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
        return NULL;
    }

    err = (*g_jvmti)->GetConstantPool(g_jvmti, target, &constant_count, &length, &bytes);
    if (err == JVMTI_ERROR_NONE && bytes != NULL && length > 0) {
        result = (*env)->NewByteArray(env, length);
        if (result != NULL) {
            (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)bytes);
        }
        (*g_jvmti)->Deallocate(g_jvmti, bytes);
    } else {
        fj_log("常量池读取失败: %s (原因: %d %s)", name_chars, (int)err, fj_jvmti_error_name(err));
    }

    (*env)->DeleteLocalRef(env, target);
    (*env)->ReleaseStringUTFChars(env, internalName, name_chars);
    return result;
}
