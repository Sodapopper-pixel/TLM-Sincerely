package com.github.tartaricacid.tlm_sincerely.priority.autowork.menu;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import org.jetbrains.annotations.Nullable;

/**
 * Independent auto-work configuration container (T-2 A5 / A6 stub).
 *
 * <p>Carries the {@link EntityMaid#getId() maid entity id} through the
 * network so the client-side container can resolve the maid on the
 * client level. Extends the TLM {@link TaskConfigContainer} so we
 * inherit the standard player inventory slots, owner/lifetime validity
 * checks and the {@code guiOpening} flag handling for free.
 *
 * <p>This is the minimum compilable foundation: no GUI Screen is
 * registered yet. The server-side {@link MenuProvider} returned by
 * {@link #createProvider(int)} writes the entity id into the buffer
 * and the client-side factory read by {@link #TYPE} decodes it.
 */
public class AutoWorkConfigContainer extends TaskConfigContainer {
    public static final MenuType<AutoWorkConfigContainer> TYPE = IMenuTypeExtension.create(
            (int windowId, Inventory inv, RegistryFriendlyByteBuf data) ->
                    new AutoWorkConfigContainer(windowId, inv, data.readInt()));

    public AutoWorkConfigContainer(int id, Inventory inventory, int entityId) {
        super(TYPE, id, inventory, entityId);
    }

    /**
     * Returns a {@link MenuProvider} that opens this container for the
     * maid with the given entity id. Used by
     * {@code serverPlayer.openMenu(provider, buf -> buf.writeInt(entityId))}.
     */
    public static MenuProvider createProvider(int entityId) {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("container.tlm_sincerely.auto_work_config");
            }

            @Override
            public AbstractContainerMenu createMenu(int index, Inventory playerInventory, Player player) {
                return new AutoWorkConfigContainer(index, playerInventory, entityId);
            }

            @Override
            public boolean shouldTriggerClientSideContainerClosingOnOpen() {
                return false;
            }
        };
    }

    @Nullable
    @Override
    public EntityMaid getMaid() {
        return super.getMaid();
    }
}
