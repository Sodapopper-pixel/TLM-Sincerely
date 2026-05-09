package com.github.tartaricacid.tlm_sincerely.mixin;

import com.github.tartaricacid.tlm_sincerely.client.gui.priority.PriorityContainer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.message.ToggleTabMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.function.Supplier;

@Mixin(ToggleTabMessage.class)
public class EntityMaidGuiMixin {
    private static final int TAB_INDEX_PRIORITY = 5;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tlmSincerely$onHandleToggleTab(ToggleTabMessage message, Supplier<NetworkEvent.Context> contextSupplier, CallbackInfo ci) {
        int tabId = getPrivateInt(message, "tabId");
        if (tabId != TAB_INDEX_PRIORITY) {
            return;
        }

        int entityId = getPrivateInt(message, "entityId");
        NetworkEvent.Context context = contextSupplier.get();
        if (!context.getDirection().getReceptionSide().isServer()) {
            return;
        }

        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            Entity entity = sender.serverLevel().getEntity(entityId);
            if (entity instanceof EntityMaid maid && maid.isOwnedBy(sender)) {
                NetworkHooks.openScreen(sender, new PriorityContainer.PriorityMenuProvider(entityId),
                        buffer -> buffer.writeInt(entityId));
            }
        });
        context.setPacketHandled(true);
        ci.cancel();
    }

    private static int getPrivateInt(Object obj, String fieldName) {
        try {
            Field field = ToggleTabMessage.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(obj);
        } catch (Exception e) {
            return 0;
        }
    }
}
