/*
 * 6.2 功能 1（JVMTI 环境获取）、功能 2（能力申请 + 位掩码）、功能 3 的注册部分。
 */
#include "fj_globals.h"

#include <stdio.h>
#include <string.h>

/* 6.4 JVMTI 错误码速查 */
const char *fj_jvmti_error_name(jvmtiError err)
{
    switch (err) {
        case JVMTI_ERROR_NONE:
            return "JVMTI_ERROR_NONE";
        case JVMTI_ERROR_FAILS_VERIFICATION:
            return "JVMTI_ERROR_FAILS_VERIFICATION(StackMapTable 缺失)";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED:
            return "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED(试图加方法)";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED:
            return "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED(试图改字段)";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED:
            return "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED(试图改继承)";
        case JVMTI_ERROR_NULL_POINTER:
            return "JVMTI_ERROR_NULL_POINTER";
        case JVMTI_ERROR_MUST_POSSESS_CAPABILITY:
            return "JVMTI_ERROR_MUST_POSSESS_CAPABILITY(能力未申请)";
        case JVMTI_ERROR_NOT_AVAILABLE:
            return "JVMTI_ERROR_NOT_AVAILABLE";
        case JVMTI_ERROR_ILLEGAL_ARGUMENT:
            return "JVMTI_ERROR_ILLEGAL_ARGUMENT";
        default:
            return "JVMTI_ERROR_其它";
    }
}

/* 功能 1：GetEnv 缓存；功能 2：一次性申请全部能力，NOT_AVAILABLE 也算成功 */
int fj_jvmti_init(JavaVM *vm)
{
    jvmtiCapabilities want;
    jvmtiCapabilities got;
    jvmtiEventCallbacks callbacks;
    jvmtiError err;
    int mask;

    if (vm == NULL) {
        return 0;
    }
    if ((*vm)->GetEnv(vm, (void **)&g_jvmti, JVMTI_VERSION_1_2) != JNI_OK || g_jvmti == NULL) {
        fprintf(stderr, "[FJ-Native] GetEnv(JVMTI_VERSION_1_2) 失败：Native 层降级\n");
        g_jvmti = NULL;
        return 0;
    }

    memset(&want, 0, sizeof(want));
    want.can_redefine_classes = 1;
    want.can_retransform_classes = 1;
    want.can_generate_all_class_hook_events = 1;
    want.can_get_bytecodes = 1;
    /* 功能 11 的 GetConstantPool 需要这一项，否则返回 99 = MUST_POSSESS_CAPABILITY */
    want.can_get_constant_pool = 1;

    err = (*g_jvmti)->AddCapabilities(g_jvmti, &want);
    if (err == JVMTI_ERROR_NOT_AVAILABLE) {
        /* 规范：部分能力不可用不阻塞（不算失败） */
        fprintf(stderr, "[FJ-Native] AddCapabilities: 部分能力不可用，按降级继续\n");
    } else if (err != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[FJ-Native] AddCapabilities 失败: %d %s\n", (int)err, fj_jvmti_error_name(err));
    }

    memset(&got, 0, sizeof(got));
    mask = 0;
    if ((*g_jvmti)->GetCapabilities(g_jvmti, &got) == JVMTI_ERROR_NONE) {
        if (got.can_redefine_classes) {
            mask |= FJ_CAP_REDEFINE_CLASSES;
        }
        if (got.can_retransform_classes) {
            mask |= FJ_CAP_RETRANSFORM_CLASSES;
        }
        if (got.can_generate_all_class_hook_events) {
            mask |= FJ_CAP_ALL_CLASS_HOOK_EVENTS;
        }
        if (got.can_get_bytecodes) {
            mask |= FJ_CAP_GET_BYTECODES;
        }
        if (got.can_get_constant_pool) {
            mask |= FJ_CAP_GET_CONSTANT_POOL;
        }
    }
    g_capabilities = mask;
    fprintf(stderr, "[FJ-Native] 能力位掩码=0x%02X (redefine=%d retransform=%d all_hook=%d get_bytecodes=%d get_constant_pool=%d)\n",
            (unsigned int)mask,
            (mask & FJ_CAP_REDEFINE_CLASSES) ? 1 : 0,
            (mask & FJ_CAP_RETRANSFORM_CLASSES) ? 1 : 0,
            (mask & FJ_CAP_ALL_CLASS_HOOK_EVENTS) ? 1 : 0,
            (mask & FJ_CAP_GET_BYTECODES) ? 1 : 0,
            (mask & FJ_CAP_GET_CONSTANT_POOL) ? 1 : 0);

    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = &fj_class_file_load_hook;
    err = (*g_jvmti)->SetEventCallbacks(g_jvmti, &callbacks, (jint)sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[FJ-Native] SetEventCallbacks 失败: %d %s\n", (int)err, fj_jvmti_error_name(err));
        g_capabilities = 0;
        return 1;
    }

    if ((mask & FJ_CAP_ALL_CLASS_HOOK_EVENTS) != 0 || (mask & FJ_CAP_RETRANSFORM_CLASSES) != 0) {
        err = (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE,
                                                   JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
        if (err != JVMTI_ERROR_NONE) {
            fprintf(stderr, "[FJ-Native] 启用 ClassFileLoadHook 失败: %d %s\n",
                    (int)err, fj_jvmti_error_name(err));
        }
    } else {
        fprintf(stderr, "[FJ-Native] 无 hook 能力，ClassFileLoadHook 未启用（降级到 Agent 层）\n");
    }

    return 1;
}

void fj_jvmti_shutdown(void)
{
    g_jvmti = NULL;
    g_capabilities = 0;
}

/* 功能 2：位掩码查询 */
JNIEXPORT jint JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeCapabilities(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return (jint)g_capabilities;
}
