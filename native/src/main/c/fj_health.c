/*
 * 6.2 功能 8：血量 Native 存储（三基线：工作值 / 上限值 / 永久基线）。
 * 约定（D9）：缺失键的 get/getMax/getBaseline 返回 NaN，调用方必须先 has。
 */
#include "fj_globals.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

static FjHealthEntry *fj_health_find(const char *uuid)
{
    FjHealthEntry *entry = g_health_head;

    while (entry != NULL) {
        if (entry->uuid != NULL && strcmp(entry->uuid, uuid) == 0) {
            return entry;
        }
        entry = entry->next;
    }
    return NULL;
}

static FjHealthEntry *fj_health_get_or_create(const char *uuid)
{
    FjHealthEntry *entry = fj_health_find(uuid);

    if (entry != NULL || uuid == NULL) {
        return entry;
    }
    entry = (FjHealthEntry *)calloc(1, sizeof(FjHealthEntry));
    if (entry == NULL) {
        return NULL;
    }
    entry->uuid = fj_strdup(uuid);
    if (entry->uuid == NULL) {
        free(entry);
        return NULL;
    }
    entry->next = g_health_head;
    g_health_head = entry;
    g_health_count++;
    return entry;
}

JNIEXPORT void JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeHealthStore_nativeHealthSet(JNIEnv *env, jclass cls,
                                                                               jstring uuid, jfloat health)
{
    const char *chars;
    FjHealthEntry *entry;

    (void)cls;
    if (env == NULL || uuid == NULL) {
        return;
    }
    chars = (*env)->GetStringUTFChars(env, uuid, NULL);
    if (chars == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }

    EnterCriticalSection(&g_lock);
    entry = fj_health_get_or_create(chars);
    if (entry != NULL) {
        if (entry->health == 0.0f && entry->max_health == 0.0f && entry->baseline == 0.0f) {
            /* 首次建立：三条基线一起定下来 */
            entry->baseline = health;
            entry->max_health = health;
        }
        entry->health = health;
    }
    LeaveCriticalSection(&g_lock);

    (*env)->ReleaseStringUTFChars(env, uuid, chars);
}

JNIEXPORT jfloat JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeHealthStore_nativeHealthGet(JNIEnv *env, jclass cls,
                                                                               jstring uuid)
{
    const char *chars;
    FjHealthEntry *entry;
    jfloat value = (jfloat)NAN;

    (void)cls;
    if (env == NULL || uuid == NULL) {
        return (jfloat)NAN;
    }
    chars = (*env)->GetStringUTFChars(env, uuid, NULL);
    if (chars == NULL) {
        (*env)->ExceptionClear(env);
        return (jfloat)NAN;
    }

    EnterCriticalSection(&g_lock);
    entry = fj_health_find(chars);
    if (entry != NULL) {
        value = entry->health;
    }
    LeaveCriticalSection(&g_lock);

    (*env)->ReleaseStringUTFChars(env, uuid, chars);
    return value;
}

JNIEXPORT void JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeHealthStore_nativeHealthSetMax(JNIEnv *env, jclass cls,
                                                                                  jstring uuid, jfloat max_health)
{
    const char *chars;
    FjHealthEntry *entry;

    (void)cls;
    if (env == NULL || uuid == NULL) {
        return;
    }
    chars = (*env)->GetStringUTFChars(env, uuid, NULL);
    if (chars == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }

    EnterCriticalSection(&g_lock);
    entry = fj_health_get_or_create(chars);
    if (entry != NULL) {
        if (entry->baseline == 0.0f) {
            entry->baseline = max_health;
        }
        entry->max_health = max_health;
        if (entry->health > max_health) {
            entry->health = max_health;
        }
    }
    LeaveCriticalSection(&g_lock);

    (*env)->ReleaseStringUTFChars(env, uuid, chars);
}

JNIEXPORT jfloat JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeHealthStore_nativeHealthGetMax(JNIEnv *env, jclass cls,
                                                                                  jstring uuid)
{
    const char *chars;
    FjHealthEntry *entry;
    jfloat value = (jfloat)NAN;

    (void)cls;
    if (env == NULL || uuid == NULL) {
        return (jfloat)NAN;
    }
    chars = (*env)->GetStringUTFChars(env, uuid, NULL);
    if (chars == NULL) {
        (*env)->ExceptionClear(env);
        return (jfloat)NAN;
    }

    EnterCriticalSection(&g_lock);
    entry = fj_health_find(chars);
    if (entry != NULL) {
        value = entry->max_health;
    }
    LeaveCriticalSection(&g_lock);

    (*env)->ReleaseStringUTFChars(env, uuid, chars);
    return value;
}

JNIEXPORT jfloat JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeHealthStore_nativeHealthGetBaseline(JNIEnv *env, jclass cls,
                                                                                       jstring uuid)
{
    const char *chars;
    FjHealthEntry *entry;
    jfloat value = (jfloat)NAN;

    (void)cls;
    if (env == NULL || uuid == NULL) {
        return (jfloat)NAN;
    }
    chars = (*env)->GetStringUTFChars(env, uuid, NULL);
    if (chars == NULL) {
        (*env)->ExceptionClear(env);
        return (jfloat)NAN;
    }

    EnterCriticalSection(&g_lock);
    entry = fj_health_find(chars);
    if (entry != NULL) {
        value = entry->baseline;
    }
    LeaveCriticalSection(&g_lock);

    (*env)->ReleaseStringUTFChars(env, uuid, chars);
    return value;
}

JNIEXPORT jboolean JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeHealthStore_nativeHealthHas(JNIEnv *env, jclass cls,
                                                                               jstring uuid)
{
    const char *chars;
    int found = 0;

    (void)cls;
    if (env == NULL || uuid == NULL) {
        return JNI_FALSE;
    }
    chars = (*env)->GetStringUTFChars(env, uuid, NULL);
    if (chars == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_FALSE;
    }

    EnterCriticalSection(&g_lock);
    found = (fj_health_find(chars) != NULL) ? 1 : 0;
    LeaveCriticalSection(&g_lock);

    (*env)->ReleaseStringUTFChars(env, uuid, chars);
    return found ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeHealthStore_nativeHealthRemove(JNIEnv *env, jclass cls,
                                                                                  jstring uuid)
{
    const char *chars;
    FjHealthEntry *previous = NULL;
    FjHealthEntry *entry;

    (void)cls;
    if (env == NULL || uuid == NULL) {
        return;
    }
    chars = (*env)->GetStringUTFChars(env, uuid, NULL);
    if (chars == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }

    EnterCriticalSection(&g_lock);
    entry = g_health_head;
    while (entry != NULL) {
        if (entry->uuid != NULL && strcmp(entry->uuid, chars) == 0) {
            if (previous == NULL) {
                g_health_head = entry->next;
            } else {
                previous->next = entry->next;
            }
            free(entry->uuid);
            free(entry);
            g_health_count--;
            break;
        }
        previous = entry;
        entry = entry->next;
    }
    LeaveCriticalSection(&g_lock);

    (*env)->ReleaseStringUTFChars(env, uuid, chars);
}

JNIEXPORT void JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeHealthStore_nativeHealthClearAll(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;

    EnterCriticalSection(&g_lock);
    while (g_health_head != NULL) {
        FjHealthEntry *next = g_health_head->next;
        free(g_health_head->uuid);
        free(g_health_head);
        g_health_head = next;
    }
    g_health_count = 0;
    LeaveCriticalSection(&g_lock);
}
