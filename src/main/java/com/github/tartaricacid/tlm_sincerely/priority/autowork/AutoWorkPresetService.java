package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Frozen per-server preset seed for the auto work switch.
 *
 * <p>The server reads {@code config/tlm_sincerely/auto_work_presets.json} once
 * per {@code MinecraftServer} and never writes it again. Its only purposes are
 * (1) the first-join S2C copy into a player's private client library and
 * (2) baking a bound snapshot for legacy maids that still only store a preset
 * id. The real library lives on each client
 * (see {@code docs/adr/0004-client-preset-library-and-maid-bound-snapshot.md}).
 *
 * <p>State is scoped to a {@link MinecraftServer} via an {@link IdentityHashMap}
 * so that integrated-server reloads do not leak presets across worlds. All
 * public methods MUST be called from the server thread.
 */
public final class AutoWorkPresetService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkPresetService.class);
    private static final Map<MinecraftServer, AutoWorkPresetService> INSTANCES = new IdentityHashMap<>();

    private final List<AutoWorkPreset> presets;
    private final UUID defaultPresetId;

    private AutoWorkPresetService(AutoWorkPresetIO.LoadedLibrary loaded) {
        this.presets = loaded.presetsInOrder();
        this.defaultPresetId = loaded.defaultPresetId();
    }

    public static AutoWorkPresetService bind(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, unused -> {
            AutoWorkPresetService service = new AutoWorkPresetService(AutoWorkPresetIO.load());
            LOGGER.info("[AutoWorkPresetService] frozen seed bound for server {}, presets={}, default={}",
                    server, service.presets.size(), service.defaultPresetId);
            return service;
        });
    }

    public static void unbind(MinecraftServer server) {
        if (INSTANCES.remove(server) != null) {
            LOGGER.info("[AutoWorkPresetService] unbound for server {}", server);
        }
    }

    public static AutoWorkPresetService getOrNull(MinecraftServer server) {
        return server == null ? null : INSTANCES.get(server);
    }

    public List<AutoWorkPreset> listPresets() {
        return Collections.unmodifiableList(new ArrayList<>(presets));
    }

    public AutoWorkPreset getPreset(UUID id) {
        if (id == null) {
            return null;
        }
        for (AutoWorkPreset preset : presets) {
            if (preset.getId().equals(id)) {
                return preset;
            }
        }
        return null;
    }

    public UUID getDefaultPresetId() {
        return defaultPresetId;
    }

    public AutoWorkPreset getDefaultPreset() {
        AutoWorkPreset preset = getPreset(defaultPresetId);
        if (preset == null && !presets.isEmpty()) {
            return presets.get(0);
        }
        return preset;
    }
}
