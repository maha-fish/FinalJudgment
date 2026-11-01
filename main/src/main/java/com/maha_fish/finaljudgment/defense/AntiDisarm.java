package com.maha_fish.finaljudgment.defense;

import com.maha_fish.finaljudgment.FinalJudgment;
import com.maha_fish.finaljudgment.core.T0Anchor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 防缴械 + 背包快照（7.12，规范 27）。
 *
 * <p>闸门：背包里有锚点武器才生效（用户要求）。</p>
 *
 * <p><b>槽位追踪表</b>覆盖：主背包 0–35、副手 -1、盔甲 -2 至 -5（规范 27）。
 * <b>允许丢弃表</b>跨 tick（主动丢弃后 10 秒内的该槽位变化视为合法）。</p>
 */
public final class AntiDisarm {

    public static final int MAIN_SLOTS = 36;
    public static final int OFFHAND_SLOT = -1;
    public static final int ARMOR_SLOT_MIN = -5;
    public static final int ARMOR_SLOT_MAX = -2;
    /** 主动丢弃后，该槽位在此窗口内的变化视为合法（跨 tick）。 */
    public static final long ALLOW_WINDOW_MS = 10_000L;

    /** player -> (slot -> lastKnown Stack signature) */
    private static final Map<UUID, Map<Integer, String>> TRACKED = new ConcurrentHashMap<>();

    // ===== setItem 拦截支持（按 Inventory 实例做键，不依赖玩家上下文）=====

    /** Inventory 实例 -> (slot -> 允许时间戳)。 */
    private static final Map<net.minecraft.world.entity.player.Inventory, Map<Integer, Long>> ALLOWED_BY_INV =
            new ConcurrentHashMap<>();

    /** Mixin 调用：原本是锚点武器、新值为空、且不在允许窗口 → 拦下。 */
    public static boolean shouldBlockSetItem(net.minecraft.world.entity.player.Inventory inventory,
                                             int slot, ItemStack incoming) {
        if (inventory == null) {
            return false;
        }
        ItemStack current = inventorySlot(inventory, slot);
        boolean currentIsWeapon = current != null && !current.isEmpty() && T0Anchor.isAnchor(current);
        boolean incomingEmpty = incoming == null || incoming.isEmpty();
        if (!currentIsWeapon || !incomingEmpty) {
            return false;
        }
        Map<Integer, Long> map = ALLOWED_BY_INV.get(inventory);
        Long allowed = map == null ? null : map.get(slot);
        return allowed == null || System.currentTimeMillis() - allowed > ALLOW_WINDOW_MS;
    }

    /** ItemTossEvent：登记该 Inventory 全部槽位的允许窗口。 */
    public static void allowAnyChange(net.minecraft.world.entity.player.Inventory inventory) {
        if (inventory == null) {
            return;
        }
        Map<Integer, Long> map = ALLOWED_BY_INV.computeIfAbsent(inventory, k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        for (int slot : allSlots()) {
            map.put(slot, now);
        }
    }

    private static ItemStack inventorySlot(net.minecraft.world.entity.player.Inventory inventory, int slot) {
        if (inventory == null) {
            return ItemStack.EMPTY;
        }
        if (slot >= 0 && slot < MAIN_SLOTS) {
            return inventory.items.get(slot);
        }
        if (slot == OFFHAND_SLOT) {
            return inventory.offhand.get(0);
        }
        int armorIndex = ARMOR_SLOT_MAX - slot;
        if (armorIndex >= 0 && armorIndex < inventory.armor.size()) {
            return inventory.armor.get(armorIndex);
        }
        return ItemStack.EMPTY;
    }
    /** player -> (slot -> 允许变化的时间戳) */
    private static final Map<UUID, Map<Integer, Long>> ALLOWED = new ConcurrentHashMap<>();
    private static final AtomicInteger RESTORED = new AtomicInteger();
    /** player -> 允许任意槽位变化的时间戳（Q 丢出、界面拖出等主动行为）。 */
    private static final Map<UUID, Long> ALLOW_ALL = new ConcurrentHashMap<>();

    private AntiDisarm() {
    }

    public static void init() {
        FinalJudgment.LOGGER.info("[FJ] AntiDisarm 已就绪（槽位 {}–{} / {}-{}-{}，允许窗口 {}ms）",
                ARMOR_SLOT_MIN, MAIN_SLOTS - 1, OFFHAND_SLOT, ARMOR_SLOT_MIN, ARMOR_SLOT_MAX, ALLOW_WINDOW_MS);
    }

    /** 主动丢弃（Q / 拖出界面）：登记"允许变化"，跨 tick 有效。 */
    public static void allowChange(ServerPlayer player, int slot) {
        if (player == null) {
            return;
        }
        ALLOWED.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                .put(slot, System.currentTimeMillis());
        FinalJudgment.LOGGER.debug("[FJ] 允许丢弃: slot={}", slot);
    }

    /** 每 tick：对比追踪表，武器槽位被清空且未授权 → 还原。 */
    public static void tick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (!T0Anchor.hasAnchorInInventory(player) && !hasTracked(player)) {
                    continue;                       // 没有武器也没有历史追踪 → 不处理
                }
                checkPlayer(player);
            } catch (Throwable t) {
                FinalJudgment.LOGGER.debug("[FJ] AntiDisarm 异常: {}", t.toString());
            }
        }
    }

    private static void checkPlayer(ServerPlayer player) {
        Map<Integer, String> previous = TRACKED.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
        for (int slot : allSlots()) {
            ItemStack current = slotStack(player, slot);
            String signature = signature(current);
            String last = previous.get(slot);
            if (last == null) {
                if (!signature.isEmpty()) {
                    previous.put(slot, signature);   // 首次登记
                }
                continue;
            }
            if (last.equals(signature)) {
                continue;
            }
            // 变化：只在"锚点武器被清空"时考虑还原
            if (last.startsWith("ANCHOR:") && signature.isEmpty() && !isAllowed(player, slot)) {
                restore(player, slot, last);
            }
            previous.put(slot, signature);
        }
    }

    private static void restore(ServerPlayer player, int slot, String signature) {
        try {
            ItemStack recovered = T0Anchor.rebuildFromSignature(signature);
            if (recovered == null) {
                return;
            }
            slotSet(player, slot, recovered);
            RESTORED.incrementAndGet();
            // 双端同步：把还原后的物品立刻推给客户端
            try {
                com.maha_fish.finaljudgment.network.SyncSender.syncInventory(player, slot, recovered);
            } catch (Throwable ignored) {
                // 同步失败不影响还原
            }
            FinalJudgment.LOGGER.info("[FJ] 防缴械: 已还原槽位 {} 的武器", slot);
        } catch (Throwable t) {
            FinalJudgment.LOGGER.debug("[FJ] 防缴械还原失败: {}", t.toString());
        }
    }

    /** 主动丢出物品：允许该玩家所有槽位的变化窗口（跨 tick 有效）。 */
    public static void allowAnyChange(ServerPlayer player) {
        if (player != null) {
            ALLOW_ALL.put(player.getUUID(), System.currentTimeMillis());
        }
    }

    private static boolean isAllowed(ServerPlayer player, int slot) {
        Long globalAt = ALLOW_ALL.get(player.getUUID());
        if (globalAt != null) {
            if (System.currentTimeMillis() - globalAt <= ALLOW_WINDOW_MS) {
                return true;
            }
            ALLOW_ALL.remove(player.getUUID());
        }
        Map<Integer, Long> allowed = ALLOWED.get(player.getUUID());
        if (allowed == null) {
            return false;
        }
        Long at = allowed.get(slot);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > ALLOW_WINDOW_MS) {
            allowed.remove(slot);
            return false;
        }
        return true;
    }

    private static boolean hasTracked(ServerPlayer player) {
        Map<Integer, String> tracked = TRACKED.get(player.getUUID());
        return tracked != null && !tracked.isEmpty();
    }

    public static int[] allSlots() {
        int[] slots = new int[MAIN_SLOTS + 1 + (ARMOR_SLOT_MAX - ARMOR_SLOT_MIN + 1)];
        int index = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            slots[index++] = slot;
        }
        slots[index++] = OFFHAND_SLOT;
        for (int slot = ARMOR_SLOT_MIN; slot <= ARMOR_SLOT_MAX; slot++) {
            slots[index++] = slot;
        }
        return slots;
    }

    private static String signature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return (T0Anchor.isAnchor(stack) ? "ANCHOR:" : "ITEM:") + stack.getItem() + ":" + stack.getCount();
    }

    public static void forget(UUID player) {
        TRACKED.remove(player);
        ALLOWED.remove(player);
    }

    public static int restored() {
        return RESTORED.get();
    }

    // ===== 槽位读写（映射到 Minecraft Inventory 的 -1/-2..-5 约定） =====

    private static ItemStack slotStack(ServerPlayer player, int slot) {
        var inventory = player.getInventory();
        if (slot >= 0 && slot < MAIN_SLOTS) {
            return inventory.items.get(slot);
        }
        if (slot == OFFHAND_SLOT) {
            return inventory.offhand.get(0);
        }
        int armorIndex = ARMOR_SLOT_MAX - slot;   // -2 → 0（脚）, -5 → 3（头）
        if (armorIndex >= 0 && armorIndex < inventory.armor.size()) {
            return inventory.armor.get(armorIndex);
        }
        return ItemStack.EMPTY;
    }

    private static void slotSet(ServerPlayer player, int slot, ItemStack stack) {
        var inventory = player.getInventory();
        if (slot >= 0 && slot < MAIN_SLOTS) {
            // 直写 NonNullList 字段（参考 ArcaneVortex 的 f_35974_），绕过可能被劫持的 setItem()
            long offset = com.maha_fish.finaljudgment.core.FieldWriter.offsetOf(
                    net.minecraft.world.entity.player.Inventory.class,
                    new String[]{"items", "f_35974_"}, "NonNullList");
            if (offset < 0 || !com.maha_fish.finaljudgment.core.FieldWriter.writeObject(
                    inventory, offset, inventory.items)) {
                inventory.items.set(slot, stack);
            } else {
                inventory.items.set(slot, stack);
            }
            return;
        }
        if (slot == OFFHAND_SLOT) {
            inventory.offhand.set(0, stack);
            return;
        }
        int armorIndex = ARMOR_SLOT_MAX - slot;
        if (armorIndex >= 0 && armorIndex < inventory.armor.size()) {
            inventory.armor.set(armorIndex, stack);
        }
    }

    /** 快照（每 1 秒由外部调用，或随 tick 复用）。 */
    public static Map<Integer, String> snapshot(ServerPlayer player) {
        Map<Integer, String> map = new HashMap<>();
        for (int slot : allSlots()) {
            String sig = signature(slotStack(player, slot));
            if (!sig.isEmpty()) {
                map.put(slot, sig);
            }
        }
        return map;
    }
}
