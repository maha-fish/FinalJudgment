package com.maha_fish.finaljudgment;

import com.maha_fish.finaljudgment.attack.AnchorWeaponItem;
import com.maha_fish.finaljudgment.common.Constants;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.SwordItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册。
 *
 * <p>Forge 1.20.1 没有 {@code DeferredRegister.Items}（1.20.2+ 才有），必须用
 * {@code DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)}。</p>
 *
 * <p>{@code test_weapon} 是**测试用武器**（Unit 9a 联调用）：构造时自动打锚点，
 * 正式武器条目由 Unit 11a 接入。</p>
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Constants.MOD_ID);

    /** 测试武器：铁剑品质，带锚点。 */
    public static final RegistryObject<Item> TEST_WEAPON = ITEMS.register("test_weapon",
            () -> new AnchorWeaponItem(Tiers.IRON, 3, -2.4F, new Item.Properties()));

    /** 正式武器（Unit 11a）。 */
    public static final RegistryObject<Item> FINAL_JUDGMENT_SWORD = ITEMS.register("final_judgment_sword",
            com.maha_fish.finaljudgment.item.FinalJudgmentItem::new);

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        // 创造模式物品栏：放进"战斗"页，方便测试（正式版可改为专属页）
        modEventBus.addListener((net.minecraftforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.COMBAT) {
                event.accept(FINAL_JUDGMENT_SWORD);
                event.accept(TEST_WEAPON);
            }
        });
    }
}
