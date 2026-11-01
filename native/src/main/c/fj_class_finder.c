/*
 * 功能 11 共用：按内部名在【已加载类】里查找。
 * 不能用 FindClass —— 它对 ModLauncher 的自定义 ClassLoader 里的类是找不到的。
 */
#include "fj_globals.h"

#include <stdio.h>
#include <string.h>

jclass fj_find_loaded_class(JNIEnv *env, const char *internal_name, int *out_matches)
{
    jint total = 0;
    jclass *classes = NULL;
    jclass result = NULL;
    int matches = 0;
    jint i;

    if (out_matches != NULL) {
        *out_matches = 0;
    }
    if (env == NULL || internal_name == NULL || g_jvmti == NULL) {
        return NULL;
    }
    if ((*g_jvmti)->GetLoadedClasses(g_jvmti, &total, &classes) != JVMTI_ERROR_NONE || classes == NULL) {
        return NULL;
    }

    for (i = 0; i < total; i++) {
        char *signature = NULL;
        char internal[512];

        if ((*g_jvmti)->GetClassSignature(g_jvmti, classes[i], &signature, NULL) != JVMTI_ERROR_NONE
                || signature == NULL) {
            continue;
        }
        if (fj_signature_to_internal(signature, internal, (int)sizeof(internal))
                && strcmp(internal, internal_name) == 0) {
            matches++;
            if (result == NULL) {
                result = (jclass)(*env)->NewLocalRef(env, classes[i]);
            }
        }
        (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)signature);
    }

    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)classes);
    if (out_matches != NULL) {
        *out_matches = matches;
    }

    /*
     * 回退：枚举不到说明该类还没被加载（例：实体字段为 null 时枚举类型可能尚未加载）。
     * FindClass 用「声明该 native 的类」的 ClassLoader 解析，正好覆盖 mod 类看 MC 类的场景。
     */
    if (result == NULL) {
        result = (*env)->FindClass(env, internal_name);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            result = NULL;
        }
    }
    return result;
}
