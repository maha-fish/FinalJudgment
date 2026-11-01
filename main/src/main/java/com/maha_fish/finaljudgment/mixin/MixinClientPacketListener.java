package com.maha_fish.finaljudgment.mixin;

import com.maha_fish.finaljudgment.client.ClientGhostGuard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端包层注入（补齐示例项目 {@code MixinClientPacketListener} 的对应能力）。
 *
 * <ul>
 *   <li>{@code handleRemoveEntities}：把被移除的实体 id 记进 {@link ClientGhostGuard}</li>
 *   <li>{@code handleAddEntity}：实体重新出现时取消黑名单</li>
 * </ul>
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"), require = 0)
    private void fj$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        try {
            for (int id : packet.getEntityIds()) {
                ClientGhostGuard.markRemoved(id);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    @Inject(method = "handleAddEntity", at = @At("HEAD"), require = 0)
    private void fj$onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        try {
            ClientGhostGuard.markAlive(packet.getId());
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}