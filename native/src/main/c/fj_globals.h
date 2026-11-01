#ifndef FJ_GLOBALS_H
#define FJ_GLOBALS_H

#include <jni.h>
#include <jvmti.h>
#include <windows.h>

/* 6.2 功能 0：类保护表容量上限 */
#define MAX_PROTECTED_CLASSES 64

/* 探针魔数："FJ" + 版本 1 */
#define FJ_PING_MAGIC 0x464A0001

/*
 * ⚠️ JNI 符号名转义规则：包名里的下划线必须写成 "_1"。
 * 本项目包名是 com.maha_fish.finaljudgment.nativebridge，
 * 因此正确符号是 Java_com_maha_1fish_finaljudgment_nativebridge_NativeBridge_<method>。
 * 写成 Java_com_maha_fish_... 会在运行时抛 UnsatisfiedLinkError（不报错、只找不到）。
 */

/* 6.2 功能 2：能力位掩码 */
#define FJ_CAP_REDEFINE_CLASSES      (1 << 0)
#define FJ_CAP_RETRANSFORM_CLASSES   (1 << 1)
#define FJ_CAP_ALL_CLASS_HOOK_EVENTS (1 << 2)
#define FJ_CAP_GET_BYTECODES         (1 << 3)
/* 功能 11 的 GetConstantPool 需要；2b 追加 */
#define FJ_CAP_GET_CONSTANT_POOL     (1 << 4)

typedef struct FjProtectedClass {
    char *internal_name;
    unsigned char *baseline;
    jint baseline_len;
} FjProtectedClass;

/* 6.2 功能 8：三基线血量条目 */
typedef struct FjHealthEntry {
    char *uuid;
    float health;      /* 工作值 */
    float max_health;  /* 上限值 */
    float baseline;    /* 永久基线 */
    struct FjHealthEntry *next;
} FjHealthEntry;

/* ===== 6.2 功能 0：全局变量 ===== */
extern JavaVM *g_jvm;
extern jvmtiEnv *g_jvmti;

extern jclass g_loggerClass;
extern jmethodID g_loggerMethod;
extern jclass g_guardClass;
extern jmethodID g_guardMethod;

extern FjProtectedClass g_protected[MAX_PROTECTED_CLASSES];
extern volatile int g_protected_count;
extern volatile int g_protect_enabled;

extern unsigned char *g_capture_bytes;
extern jint g_capture_len;
extern char *g_capture_target;
extern volatile int g_capture_enabled;

extern unsigned char *g_hook_bytes;
extern jint g_hook_bytes_len;
extern char *g_hook_target_class;

extern volatile int g_self_retransform_count;

extern FjHealthEntry *g_health_head;
extern volatile int g_health_count;

extern volatile int g_capabilities;
extern volatile int g_bootstrap_injected;
extern CRITICAL_SECTION g_lock;

/* hook 统计：回调内只做纯 C 自增，绝不调用 Java（避免类加载递归） */
extern volatile int g_hook_events_total;
extern volatile int g_hook_events_self;
extern volatile int g_hook_events_other;

/* 6.2 功能 12：计数器语义（规范 12，禁止布尔） */
#define IS_SELF_RETRANSFORM() (g_self_retransform_count > 0)

/* 6.2 功能 9：日志桥接（自动 attach / detach，失败回退 stderr） */
void fj_log(const char *fmt, ...);

/* 6.2 功能 12 */
void fj_selfmark_enter(void);
void fj_selfmark_exit(void);

/* 6.4 错误码名 */
const char *fj_jvmti_error_name(jvmtiError err);

/* 取类内部名（斜杠分隔），成功返回 1 */
int fj_class_internal_name(JNIEnv *env, jclass klass, char *buf, int buflen);

/* 6.2 功能 1、2、3 */
int fj_jvmti_init(JavaVM *vm);
void fj_jvmti_shutdown(void);
void JNICALL fj_class_file_load_hook(jvmtiEnv *jvmti_env, JNIEnv *jni_env,
                                     jclass class_being_redefined, jobject loader,
                                     const char *name, jobject protection_domain,
                                     jint class_data_len, const unsigned char *class_data,
                                     jint *new_class_data_len, unsigned char **new_class_data);

/* ===================== 2b 声明 ===================== */

/*
 * 线程本地重入标记（D10）。
 * 用 JVMTI 线程本地存储（SetThreadLocalStorage / GetThreadLocalStorage），
 * 不用 GCC __thread —— 后者在 MinGW 上走 emutls，会给 dll 引入 libgcc_s_seh-1.dll 运行期依赖。
 * 置位期间禁止任何 Java 回调（含日志）。
 */
void fj_reentrancy_enter(void);
void fj_reentrancy_exit(void);
int fj_in_java_callback(void);
#define FJ_IN_JAVA_CALLBACK() (fj_in_java_callback() != 0)

/* 保护段统计（hook 内只计数，不写日志） */
extern volatile int g_protect_fast_pass;
extern volatile int g_protect_slow_pass;
extern volatile int g_protect_reverted;
extern volatile int g_protect_degraded;

/* 工具 */
char *fj_strdup(const char *text);
int fj_signature_to_internal(const char *signature, char *buf, int buflen);

/* 6.2 功能 11 共用：按内部名在已加载类里查找（支持任意 ClassLoader） */
jclass fj_find_loaded_class(JNIEnv *env, const char *internal_name, int *out_matches);

/* 6.2 功能 6：类保护表 */
int fj_register_protected(const char *internal_name, const jbyte *baseline, jint baseline_len);
int fj_protect_decide(JNIEnv *jni_env, jvmtiEnv *jvmti_env, const char *name,
                      const unsigned char *current, jint current_len,
                      unsigned char **out_bytes, jint *out_len);

#endif /* FJ_GLOBALS_H */
