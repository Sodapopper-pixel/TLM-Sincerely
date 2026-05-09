package com.github.tartaricacid.tlm_sincerely.client.gui.priority;

import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.extensions.IForgeMenuType;
import org.jetbrains.annotations.Nullable;

public class PriorityContainer extends AbstractMaidContainer {
    private static MenuType<PriorityContainer> registeredType;

    public static MenuType<PriorityContainer> createType() {
        registeredType = IForgeMenuType.create(
                (id, inv, data) -> new PriorityContainer(id, inv, data.readInt()));
        return registeredType;
    }

    public PriorityContainer(int id, Inventory inventory, int entityId) {
        super(registeredType, id, inventory, entityId);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    public static class PriorityMenuProvider implements MenuProvider {
        private final int entityId;

        public PriorityMenuProvider(int entityId) {
            this.entityId = entityId;
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("gui.tlm_sincerely.priority.title");
        }

        @Nullable
        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
            return new PriorityContainer(id, inventory, entityId);
        }
    }
}
