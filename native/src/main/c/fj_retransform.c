/*
 * 补扫（D6/D7）：对【已经加载】的类补触发 ClassFileLoadHook。
 *
 * 背景：dll 由 System.load 在 Live 阶段加载，此时 Entity / LivingEntity / 第三方 mod 类
 * 早已加载完毕，ClassFileLoadHook 对"过去"的加载不会回调。RetransformClasses 会对已加载类
 * 重新触发该回调，因此用它把钩子追回去。
 *
 * 约束：
 *  - 用 RetransformClasses，不用 RedefineClasses（vanilla 类允许 retransform，禁止 redefine，规范 17/31）。
 *  - 整批用我方标记包住（规范 12），避免保护段把自己的补扫当成外部篡改。
 *  - 单类失败隔离（规范 39/40），返回成功计数，失败明细走日志。
 */
#include "fj_globals.h"

#include <stdio.h>

JNIEXPORT jint JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeRetransformClasses(JNIEnv *env, jclass cls,
                                                                                   jobjectArray targets)
{
    jsize count;
    jint ok = 0;
    jsize i;

    (void)cls;

    if (env == NULL || targets == NULL) {
        return 0;
    }
    if (g_jvmti == NULL) {
        fj_log("补扫跳过: JVMTI 环境不可用");
        return 0;
    }

    count = (*env)->GetArrayLength(env, targets);
    if (count <= 0) {
        return 0;
    }

    fj_selfmark_enter();
    for (i = 0; i < count; i++) {
        jobject element = (*env)->GetObjectArrayElement(env, targets, i);
        jclass target;
        jvmtiError err;

        if (element == NULL) {
            continue;
        }
        target = (jclass)element;

        err = (*g_jvmti)->RetransformClasses(g_jvmti, 1, &target);
        if (err == JVMTI_ERROR_NONE) {
            ok++;
        } else {
            char internal_name[256];
            if (fj_class_internal_name(env, target, internal_name, (int)sizeof(internal_name))) {
                fj_log("补扫失败: %s (原因: %d %s)", internal_name, (int)err, fj_jvmti_error_name(err));
            } else {
                fj_log("补扫失败: <unknown> (原因: %d %s)", (int)err, fj_jvmti_error_name(err));
            }
        }

        (*env)->DeleteLocalRef(env, element);
    }
    fj_selfmark_exit();

    /* 汇总一行（频率 = 调用次数，不是每 tick，不会刷屏） */
    fj_log("补扫: 请求 %d 个, 成功 %d, 失败 %d; hook 累计事件 self=%d other=%d",
           (int)count, (int)ok, (int)(count - ok),
           (int)g_hook_events_self, (int)g_hook_events_other);

    return ok;
}
