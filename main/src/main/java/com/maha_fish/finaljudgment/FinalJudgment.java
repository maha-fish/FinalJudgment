package com.maha_fish.finaljudgment;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主入口（7.1 功能 1）。构造器只做注册，不写业务逻辑。
 *
 * <p>网络包必须在<b>构造器阶段</b>注册：Forge 有 impl channels is locked 机制，
 * 在 commonSetup 之后注册会失败（7.11 / 10.0.1）。</p>
 */
@Mod("finaljudgment")
public final class FinalJudgment {

    public static final Logger LOGGER = LoggerFactory.getLogger("finaljudgment");

    public FinalJudgment() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.register(modEventBus);
        NetworkHandler.init();
        // 注：SRG 属性桥已废弃（dev 下 common 类不在运行期 classpath，且 coremod 已有本地候选表）
        modEventBus.addListener(this::commonSetup);
        LOGGER.info("[FJ] 构造器阶段完成: 物品注册器 / 网络通道 / commonSetup 监听器已登记");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        InitPipeline.run();
    }
}
