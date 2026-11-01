/*
 * 6.2 功能 7：直杀实体 —— JNI 直接写 removalReason 字段，绕过所有 Java 方法。
 *
 * D1：字段候选名由 Java 传入（ENV_SPEC 十一：C 侧禁止硬编码 SRG 名）。
 * 流程：GetObjectClass → 候选名逐个 GetFieldID → desc 反推枚举内部名 → values() → ordinal → SetObjectField
 */
#include "fj_globals.h"

#include <stdio.h>
#include <string.h>

JNIEXPORT jboolean JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeKill_nativeKillEntity(JNIEnv *env, jclass cls,
                                                                         jobject entity,
                                                                         jobjectArray candidates,
                                                                         jstring fieldDesc,
                                                                         jint reasonOrdinal)
{
    jclass entity_class;
    jfieldID field = NULL;
    const char *desc_chars = NULL;
    char enum_internal[512];
    jclass enum_class;
    char values_signature[600];
    jmethodID values_method;
    jobjectArray values;
    jobject reason;
    jsize values_len;
    jsize count;
    jsize i;
    int matches = 0;

    (void)cls;

    if (env == NULL || entity == NULL || candidates == NULL || fieldDesc == NULL || reasonOrdinal < 0) {
        return JNI_FALSE;
    }

    entity_class = (*env)->GetObjectClass(env, entity);
    if (entity_class == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }

    desc_chars = (*env)->GetStringUTFChars(env, fieldDesc, NULL);
    if (desc_chars == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, entity_class);
        return JNI_FALSE;
    }

    /* 候选名逐个尝试（Java 传入，C 侧无任何 SRG 字面量） */
    count = (*env)->GetArrayLength(env, candidates);
    for (i = 0; i < count; i++) {
        jstring candidate = (jstring)(*env)->GetObjectArrayElement(env, candidates, i);
        const char *candidate_chars;

        if (candidate == NULL) {
            continue;
        }
        candidate_chars = (*env)->GetStringUTFChars(env, candidate, NULL);
        if (candidate_chars != NULL) {
            field = (*env)->GetFieldID(env, entity_class, candidate_chars, desc_chars);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                field = NULL;
            }
            (*env)->ReleaseStringUTFChars(env, candidate, candidate_chars);
        }
        (*env)->DeleteLocalRef(env, candidate);
        if (field != NULL) {
            break;
        }
    }

    if (field == NULL) {
        fj_log("直杀失败: 目标类找不到候选字段 (desc=%s, 候选数=%d)", desc_chars, (int)count);
        (*env)->ReleaseStringUTFChars(env, fieldDesc, desc_chars);
        (*env)->DeleteLocalRef(env, entity_class);
        return JNI_FALSE;
    }

    /* 描述符 Lxxx; → 枚举内部名 */
    if (!fj_signature_to_internal(desc_chars, enum_internal, (int)sizeof(enum_internal))) {
        fj_log("直杀失败: 字段描述符不是对象类型 (%s)", desc_chars);
        (*env)->ReleaseStringUTFChars(env, fieldDesc, desc_chars);
        (*env)->DeleteLocalRef(env, entity_class);
        return JNI_FALSE;
    }

    enum_class = fj_find_loaded_class(env, enum_internal, &matches);
    if (enum_class == NULL) {
        fj_log("直杀失败: 找不到枚举类 %s", enum_internal);
        (*env)->ReleaseStringUTFChars(env, fieldDesc, desc_chars);
        (*env)->DeleteLocalRef(env, entity_class);
        return JNI_FALSE;
    }

    snprintf(values_signature, sizeof(values_signature), "()[L%s;", enum_internal);
    values_method = (*env)->GetStaticMethodID(env, enum_class, "values", values_signature);
    if (values_method == NULL) {
        (*env)->ExceptionClear(env);
        fj_log("直杀失败: %s 没有 values() %s", enum_internal, values_signature);
        (*env)->ReleaseStringUTFChars(env, fieldDesc, desc_chars);
        (*env)->DeleteLocalRef(env, enum_class);
        (*env)->DeleteLocalRef(env, entity_class);
        return JNI_FALSE;
    }

    values = (jobjectArray)(*env)->CallStaticObjectMethod(env, enum_class, values_method);
    if (values == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        fj_log("直杀失败: 调用 %s.values() 出错", enum_internal);
        (*env)->ReleaseStringUTFChars(env, fieldDesc, desc_chars);
        (*env)->DeleteLocalRef(env, enum_class);
        (*env)->DeleteLocalRef(env, entity_class);
        return JNI_FALSE;
    }

    values_len = (*env)->GetArrayLength(env, values);
    if (reasonOrdinal >= values_len) {
        fj_log("直杀失败: ordinal %d 越界 (%s 只有 %d 个常量)",
               (int)reasonOrdinal, enum_internal, (int)values_len);
        (*env)->ReleaseStringUTFChars(env, fieldDesc, desc_chars);
        (*env)->DeleteLocalRef(env, values);
        (*env)->DeleteLocalRef(env, enum_class);
        (*env)->DeleteLocalRef(env, entity_class);
        return JNI_FALSE;
    }

    reason = (*env)->GetObjectArrayElement(env, values, reasonOrdinal);
    if (reason != NULL) {
        (*env)->SetObjectField(env, entity, field, reason);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        fj_log("直杀失败: SetObjectField 出错");
        if (reason != NULL) {
            (*env)->DeleteLocalRef(env, reason);
        }
        (*env)->ReleaseStringUTFChars(env, fieldDesc, desc_chars);
        (*env)->DeleteLocalRef(env, values);
        (*env)->DeleteLocalRef(env, enum_class);
        (*env)->DeleteLocalRef(env, entity_class);
        return JNI_FALSE;
    }

    fj_log("直杀成功: 已写入 %s.ordinal=%d", enum_internal, (int)reasonOrdinal);

    if (reason != NULL) {
        (*env)->DeleteLocalRef(env, reason);
    }
    (*env)->ReleaseStringUTFChars(env, fieldDesc, desc_chars);
    (*env)->DeleteLocalRef(env, values);
    (*env)->DeleteLocalRef(env, enum_class);
    (*env)->DeleteLocalRef(env, entity_class);
    return reason != NULL ? JNI_TRUE : JNI_FALSE;
}
