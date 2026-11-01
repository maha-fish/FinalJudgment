/*
 * 6.2 功能 12：我方 retransform 标记。
 * 用计数器而非布尔（规范 12）：多线程同时 retransform 时布尔标志会被覆盖。
 */
#include "fj_globals.h"

static volatile int g_selfmark_warned = 0;

void fj_selfmark_enter(void)
{
    __sync_fetch_and_add(&g_self_retransform_count, 1);
}

void fj_selfmark_exit(void)
{
    int value = __sync_sub_and_fetch(&g_self_retransform_count, 1);

    if (value < 0) {
        /* 重复释放：钳制回 0，避免永久负值让 IS_SELF_RETRANSFORM() 恒为假 */
        __sync_bool_compare_and_swap(&g_self_retransform_count, value, 0);
        if (!g_selfmark_warned) {
            g_selfmark_warned = 1;
            fj_log("我方标记计数异常: 重复释放 (已钳制为 0)");
        }
    }
}

JNIEXPORT void JNICALL
Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_nativeSetSelfRetransform(JNIEnv *env, jclass cls,
                                                                                   jboolean active)
{
    (void)env;
    (void)cls;

    if (active) {
        fj_selfmark_enter();
    } else {
        fj_selfmark_exit();
    }
}
