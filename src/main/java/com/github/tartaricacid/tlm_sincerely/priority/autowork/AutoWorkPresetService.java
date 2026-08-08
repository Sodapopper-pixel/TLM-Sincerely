package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-server preset library for the auto work switch (T-2 A2).
 *
 * <p>State is scoped to a {@link MinecraftServer} via an {@link IdentityHashMap}
 * so that integrated-server reloads do not leak presets across worlds. The
 * service is NOT thread-safe: every public method MUST be called from the
 * server thread.
 *
 * <p>Backing storage is a {@link LinkedHashMap} that preserves insertion order
 * (matches the v3 JSON array order). No concurrent collections are used.
 *
 * <p>Bind/unbind is driven by {@code SincerelyExtension}'s server lifecycle
 * event handlers so that a single class owns the lifecycle ordering.
 */
public final class AutoWorkPresetService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkPresetService.class);
    private static final Map<MinecraftServer, AutoWorkPresetService> INSTANCES = new IdentityHashMap<>();

    private final Map<UUID, AutoWorkPreset> presets = new LinkedHashMap<>();
    private UUID defaultPresetId;
    private boolean dirty = false;

    private AutoWorkPresetService(AutoWorkPresetIO.LoadedLibrary loaded) {
        this.presets.putAll(loaded.presets());
        this.defaultPresetId = loaded.defaultPresetId();
    }

    public static AutoWorkPresetService bind(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, unused -> {
            AutoWorkPresetIO.LoadedLibrary loaded = AutoWorkPresetIO.loadOrCreate();
            AutoWorkPresetService service = new AutoWorkPresetService(loaded);
            LOGGER.info("[AutoWorkPresetService] bound for server {}, presets={}, default={}",
                    server, service.presets.size(), service.defaultPresetId);
            return service;
        });
    }

    public static void unbind(MinecraftServer server) {
        AutoWorkPresetService service = INSTANCES.remove(server);
        if (service != null) {
            if (service.dirty) {
                // Best-effort flush; if it fails the next service instance
                // will re-read from disk on next bind.
                service.persistNow();
            }
            LOGGER.info("[AutoWorkPresetService] unbound for server {}", server);
        }
    }

    public static AutoWorkPresetService get(MinecraftServer server) {
        return INSTANCES.get(server);
    }

    public static AutoWorkPresetService getOrNull(MinecraftServer server) {
        return server == null ? null : INSTANCES.get(server);
    }

    public List<AutoWorkPreset> listPresets() {
        return Collections.unmodifiableList(new ArrayList<>(presets.values()));
    }

    public AutoWorkPreset getPreset(UUID id) {
        return presets.get(id);
    }

    public UUID getDefaultPresetId() {
        return defaultPresetId;
    }

    public AutoWorkPreset getDefaultPreset() {
        AutoWorkPreset preset = presets.get(defaultPresetId);
        if (preset == null && !presets.isEmpty()) {
            return presets.values().iterator().next();
        }
        return preset;
    }

    public boolean setDefaultPreset(UUID id) {
        if (!presets.containsKey(id)) {
            return false;
        }
        this.defaultPresetId = id;
        markDirty();
        return true;
    }

    public AutoWorkPreset createPreset(String name) {
        AutoWorkPreset preset = new AutoWorkPreset(UUID.randomUUID(), name, new ArrayList<>());
        presets.put(preset.getId(), preset);
        markDirty();
        return preset;
    }

    public boolean deletePreset(UUID id) {
        if (presets.size() <= 1) {
            return false;
        }
        if (!presets.containsKey(id)) {
            return false;
        }
        presets.remove(id);
        if (id.equals(defaultPresetId)) {
            defaultPresetId = presets.keySet().iterator().next();
        }
        markDirty();
        return true;
    }

    public boolean renamePreset(UUID id, String newName) {
        AutoWorkPreset preset = presets.get(id);
        if (preset == null || newName == null || newName.isEmpty()) {
            return false;
        }
        preset.setName(newName);
        markDirty();
        return true;
    }

    public boolean addTask(UUID presetId, ResourceLocation task) {
        AutoWorkPreset preset = presets.get(presetId);
        if (preset == null) {
            return false;
        }
        boolean added = preset.addTask(task);
        if (added) {
            markDirty();
        }
        return added;
    }

    public boolean removeTask(UUID presetId, ResourceLocation task) {
        AutoWorkPreset preset = presets.get(presetId);
        if (preset == null) {
            return false;
        }
        boolean removed = preset.removeTask(task);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public boolean moveTask(UUID presetId, ResourceLocation task, int targetIndex) {
        AutoWorkPreset preset = presets.get(presetId);
        if (preset == null) {
            return false;
        }
        int previous = preset.getOrder().indexOf(task);
        preset.moveTask(task, targetIndex);
        if (preset.getOrder().indexOf(task) != previous) {
            markDirty();
            return true;
        }
        return false;
    }

    /**
     * Returns the active preset for a maid: the preset referenced by
     * {@code state.presetId()} if it exists, otherwise the default preset.
     */
    public AutoWorkPreset resolveForMaid(AutoWorkState state) {
        if (state != null) {
            AutoWorkPreset explicit = presets.get(state.presetId());
            if (explicit != null) {
                return explicit;
            }
        }
        return getDefaultPreset();
    }

    /** Forces an immediate write to disk; primarily for shutdown / tests. */
    public void persistNow() {
        AutoWorkPresetIO.save(new AutoWorkPresetIO.LoadedLibrary(presets, defaultPresetId));
        dirty = false;
    }

    private void markDirty() {
        this.dirty = true;
    }
}
