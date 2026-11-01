package com.maha_fish.finaljudgment.attack;

import com.maha_fish.finaljudgment.FinalJudgment;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Klass 指针替换（改造自 super-steve 的 {@code PLZBase.klassPtr}）。
 *
 * <p><b>原理</b>：HotSpot 对象头第二个机器字是压缩的 Klass 指针（偏移 8，compressed oops 下 4 字节）。
 * 把某个实例的 Klass 指针改成"另一个布局完全相同的类"，该实例的<b>虚方法分派立即改变</b>——
 * 相当于运行时换掉它的 vtable。super-steve 用它把实体换成自己的 Safe 子类，
 * 从而免疫伤害、拒绝移除。</p>
 *
 * <p><b>安全边界（我们额外加的）</b>：</p>
 * <ul>
 *   <li>目标类必须是原类的 <b>子类且不新增字段</b>（布局完全一致），否则字段偏移会错位 → 崩溃；</li>
 *   <li>生成的子类只重写 3 个方法：{@code isRemoved()}→true、{@code getRemovalReason()}→DISCARDED、
 *       {@code onRemove(reason)}→空实现；</li>
 *   <li>整个过程包在 try-catch 里，失败只打 WARN，不影响原击杀链；</li>
 *   <li>{@code -Dfj.klassPtr=false} 可关闭（默认开启）。</li>
 * </ul>
 */
public final class KlassSwap {

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("fj.klassPtr", "true"));
    /** 压缩 Klass 指针在对象头中的偏移（HotSpot 64 位 + compressed oops）。 */
    private static final long KLASS_OFFSET = 8L;

    private static final Map<Class<?>, Class<?>> DEAD_CLASSES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, MethodHandles.Lookup> LOOKUPS = new ConcurrentHashMap<>();

    private KlassSwap() {
    }

    /**
     * 把实例的 Klass 换成"已死"子类，使其 {@code isRemoved()} 恒真、{@code onRemove} 无效。
     *
     * @return true 表示替换成功
     */
    public static boolean markRemoved(Object entity) {
        if (!ENABLED || entity == null) {
            return false;
        }
        try {
            Class<?> original = entity.getClass();
            Class<?> deadClass = DEAD_CLASSES.computeIfAbsent(original, KlassSwap::defineDeadSubclass);
            if (deadClass == null) {
                return false;
            }
            return writeKlass(entity, deadClass);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] KlassSwap 失败（不影响击杀链）: {}", t.toString());
            return false;
        }
    }

    /** 直接写 Klass 指针；目标类必须是原类子类且字段布局一致。 */
    public static boolean writeKlass(Object instance, Class<?> targetClass) {
        if (instance == null || targetClass == null) {
            return false;
        }
        if (!layoutCompatible(instance.getClass(), targetClass)) {
            FinalJudgment.LOGGER.warn("[FJ] KlassSwap 拒绝：{} 与 {} 字段布局不一致",
                    instance.getClass().getName(), targetClass.getName());
            return false;
        }
        Object unsafe = com.maha_fish.finaljudgment.core.UnsafeAccess.unsafe();
        if (!(unsafe instanceof sun.misc.Unsafe raw)) {
            return false;
        }
        try {
            int targetKlass = readKlassInt(raw, newPrototype(raw, targetClass));
            raw.putInt(instance, KLASS_OFFSET, targetKlass);
            FinalJudgment.LOGGER.debug("[FJ] KlassSwap: {} → {}", instance.getClass().getName(), targetClass.getName());
            return true;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.warn("[FJ] KlassSwap.writeKlass 失败: {}", t.toString());
            return false;
        }
    }

    // ===================== 内部实现 =====================

    private static int readKlassInt(sun.misc.Unsafe unsafe, Object instance) {
        return unsafe.getInt(instance, KLASS_OFFSET);
    }

    /** 用 Unsafe 分配一个目标类实例（不调用构造器），仅用于取它的 Klass 指针。 */
    private static Object newPrototype(sun.misc.Unsafe unsafe, Class<?> type) throws Exception {
        return unsafe.allocateInstance(type);
    }

    /** 字段布局一致性检查：偏移与类型必须一一对应（否则改 Klass 会读错内存）。 */
    private static boolean layoutCompatible(Class<?> a, Class<?> b) {
        if (a == b) {
            return true;
        }
        if (!a.isAssignableFrom(b)) {
            return false;
        }
        try {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) com.maha_fish.finaljudgment.core.UnsafeAccess.unsafe();
            if (unsafe == null) {
                return false;
            }
            // 子类不得新增实例字段
            for (java.lang.reflect.Field field : b.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 生成"已死"子类：不新增任何字段，只重写三个方法。
     * 方法名从运行期实际类里查（dev 是 MCP 名、生产是 SRG 名），避免硬编码。
     */
    private static Class<?> defineDeadSubclass(Class<?> original) {
        try {
            Method isRemoved = findNoArg(original, "isRemoved", "m_213877_");
            Method getReason = findNoArg(original, "getRemovalReason", "m_213780_");
            Method onRemove = findOneArg(original, "onRemove", "m_142472_");
            if (isRemoved == null) {
                return null;
            }
            String originalName = original.getName().replace('.', '/');
            String deadName = originalName + "$$FJDead";

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                    deadName, null, originalName, null);

            // isRemoved() -> true
            MethodVisitor m1 = writer.visitMethod(Opcodes.ACC_PUBLIC, isRemoved.getName(), "()Z", null, null);
            m1.visitCode();
            m1.visitInsn(Opcodes.ICONST_1);
            m1.visitInsn(Opcodes.IRETURN);
            m1.visitMaxs(1, 1);
            m1.visitEnd();

            // getRemovalReason() -> DISCARDED
            if (getReason != null) {
                MethodVisitor m2 = writer.visitMethod(Opcodes.ACC_PUBLIC, getReason.getName(),
                        "()Lnet/minecraft/world/entity/Entity$RemovalReason;", null, null);
                m2.visitCode();
                m2.visitFieldInsn(Opcodes.GETSTATIC,
                        "net/minecraft/world/entity/Entity$RemovalReason", "DISCARDED",
                        "Lnet/minecraft/world/entity/Entity$RemovalReason;");
                m2.visitInsn(Opcodes.ARETURN);
                m2.visitMaxs(1, 1);
                m2.visitEnd();
            }

            // onRemove(RemovalReason) -> 空实现（阻止模组回调重新挂回）
            if (onRemove != null) {
                MethodVisitor m3 = writer.visitMethod(Opcodes.ACC_PUBLIC, onRemove.getName(),
                        "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", null, null);
                m3.visitCode();
                m3.visitInsn(Opcodes.RETURN);
                m3.visitMaxs(0, 2);
                m3.visitEnd();
            }

            writer.visitEnd();
            byte[] bytes = writer.toByteArray();

            MethodHandles.Lookup lookup = LOOKUPS.computeIfAbsent(original, type -> {
                try {
                    return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
                } catch (Throwable t) {
                    return null;
                }
            });
            if (lookup == null) {
                return null;
            }
            Class<?> defined = lookup.defineClass(bytes);
            FinalJudgment.LOGGER.info("[FJ] KlassSwap: 已生成已死子类 {}（父类 {}）",
                    defined.getName(), original.getName());
            return defined;
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 生成已死子类失败: {}", t.toString());
            return null;
        }
    }

    private static Method findNoArg(Class<?> type, String... names) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                for (String name : names) {
                    if (method.getName().equals(name)) {
                        return method;
                    }
                }
            }
        }
        return null;
    }

    private static Method findOneArg(Class<?> type, String... names) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 1) {
                    continue;
                }
                for (String name : names) {
                    if (method.getName().equals(name)) {
                        return method;
                    }
                }
            }
        }
        return null;
    }
}
