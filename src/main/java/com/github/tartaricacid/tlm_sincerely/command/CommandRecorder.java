package com.github.tartaricacid.tlm_sincerely.command;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link CommandSource} that captures everything a command reports back
 * without changing the identity of the wrapped source.
 *
 * <p>{@link #shouldInformAdmins()} returns {@code false} so that command
 * feedback is not broadcast to every operator on the server.
 */
public final class CommandRecorder implements CommandSource {
    private final List<Component> messages = new ArrayList<>();

    public List<Component> messages() {
        return List.copyOf(messages);
    }

    @Override
    public void sendSystemMessage(Component component) {
        messages.add(component);
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }
}
