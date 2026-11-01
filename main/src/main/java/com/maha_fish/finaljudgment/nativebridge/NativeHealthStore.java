package com.maha_fish.finaljudgment.nativebridge;

/** 血量 Native 存储的 8 个 JNI 声明（与 dll 导出符号一一对应）。 */
public final class NativeHealthStore {

    private NativeHealthStore() {
    }

    public static native void nativeHealthSet(String uuid, float health);

    public static native float nativeHealthGet(String uuid);

    public static native void nativeHealthSetMax(String uuid, float maxHealth);

    public static native float nativeHealthGetMax(String uuid);

    public static native float nativeHealthGetBaseline(String uuid);

    public static native boolean nativeHealthHas(String uuid);

    public static native void nativeHealthRemove(String uuid);

    public static native void nativeHealthClearAll();
}
