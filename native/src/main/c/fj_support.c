/*
 * 2b 支撑层：线程本地重入标记、保护段统计、字符串与签名工具。
 */
#include "fj_globals.h"

#include <stdlib.h>
#include <string.h>

/* D10：线程本地（JVMTI 线程本地存储），不是全局标记 */
void fj_reentrancy_enter(void)
{
    if (g_jvmti != NULL) {
        /* thread = NULL 表示当前线程 */
        (*g_jvmti)->SetThreadLocalStorage(g_jvmti, NULL, (void *)1);
    }
}

void fj_reentrancy_exit(void)
{
    if (g_jvmti != NULL) {
        (*g_jvmti)->SetThreadLocalStorage(g_jvmti, NULL, NULL);
    }
}

int fj_in_java_callback(void)
{
    void *value = NULL;

    if (g_jvmti == NULL) {
        return 0;
    }
    if ((*g_jvmti)->GetThreadLocalStorage(g_jvmti, NULL, &value) != JVMTI_ERROR_NONE) {
        return 0;
    }
    return value != NULL;
}

volatile int g_protect_fast_pass = 0;
volatile int g_protect_slow_pass = 0;
volatile int g_protect_reverted = 0;
volatile int g_protect_degraded = 0;

char *fj_strdup(const char *text)
{
    size_t length;
    char *copy;

    if (text == NULL) {
        return NULL;
    }
    length = strlen(text) + 1;
    copy = (char *)malloc(length);
    if (copy == NULL) {
        return NULL;
    }
    memcpy(copy, text, length);
    return copy;
}

/* "Lnet/minecraft/...;" → "net/minecraft/..."；非对象签名返回 0 */
int fj_signature_to_internal(const char *signature, char *buf, int buflen)
{
    int length;
    int start;
    int end;
    int count;

    if (signature == NULL || buf == NULL || buflen < 2) {
        return 0;
    }
    length = (int)strlen(signature);
    if (length < 3 || signature[0] != 'L') {
        return 0;
    }
    start = 1;
    end = (signature[length - 1] == ';') ? length - 1 : length;
    count = end - start;
    if (count <= 0) {
        return 0;
    }
    if (count > buflen - 1) {
        count = buflen - 1;
    }
    memcpy(buf, signature + start, (size_t)count);
    buf[count] = '\0';
    return 1;
}
