package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-server facade for the maid bound snapshot.
 *
 * <p>A maid stores its scheduling order in its own TLM task data
 * ({@link AutoWorkTaskDataKeys#STATE_KEY}); the server scheduler reads only
 * that order. Legacy states that only carry a preset id are baked from the
 * frozen seed the first time they are touched after this feature is loaded.
 *
 * <p>All public methods must be called from the server thread; they read/write
 * the maid's task data map which is itself a non-thread-safe structure.
 */
public final class AutoWorkStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkStateService.class);
    private static final Map<MinecraftServer, AutoWorkStateService> INSTANCES = new IdentityHashMap<>();
    private static final int MAX_SNAPSHOT_TASKS = 512;

    private final MinecraftServer server;

    private AutoWorkStateService(MinecraftServer server) {
        this.server = server;
    }

    public static AutoWorkStateService bind(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, AutoWorkStateService::new);
    }

    public static void unbind(MinecraftServer server) {
        AutoWorkStateService service = INSTANCES.remove(server);
        if (service != null) {
            LOGGER.info("[AutoWorkStateService] unbound for server {}", server);
        }
    }

    public static AutoWorkStateService getOrNull(MinecraftServer server) {
        return server == null ? null : INSTANCES.get(server);
    }

    /**
     * Reads the bound snapshot. Maids without stored data get a non-persisted
     * fallback that mirrors the frozen seed's default preset.
     */
    public AutoWorkState getState(EntityMaid maid) {
        AutoWorkState stored = maid.getData(AutoWorkTaskDataKeys.STATE_KEY);
        if (stored != null) {
            return stored;
        }
        AutoWorkPreset defaultPreset = defaultPreset();
        if (defaultPreset == null) {
            return AutoWorkState.disabledFallback(AutoWorkState.NO_PRESET_ID, "", List.of());
        }
        return AutoWorkState.disabledFallback(defaultPreset.getId(), defaultPreset.getName(),
                defaultPreset.getOrder());
    }

    /**
     * Bakes the bound snapshot from the frozen seed when the maid still only
     * stores a preset id, then persists it. No-op for already-baked states.
     */
    public AutoWorkState ensureBoundSnapshot(EntityMaid maid) {
        AutoWorkState state = getState(maid);
        if (state.snapshotBaked()) {
            return state;
        }
        AutoWorkState baked = bakeFromSeed(state);
        if (baked == null) {
            return state;
        }
        setState(maid, baked);
        LOGGER.debug("[AutoWorkState] baked bound snapshot for maid={} preset={} tasks={}",
                maid.getUUID(), baked.presetId(), baked.order().size());
        return baked;
    }

    /** Persists the state via the maid's TLM task data and syncs it to clients. */
    public void setState(EntityMaid maid, AutoWorkState state) {
        maid.setAndSyncData(AutoWorkTaskDataKeys.STATE_KEY, state);
    }

    public void setEnabled(EntityMaid maid, boolean enabled) {
        AutoWorkState current = getState(maid);
        if (current.enabled() == enabled) {
            return;
        }
        if (enabled) {
            current = ensureBoundSnapshot(maid);
        }
        setState(maid, current.withEnabled(enabled));
    }

    /**
     * Binds a full preset snapshot (id/name/order) to the maid. Selecting the
     * same UUID again re-bakes from the client library's current copy.
     */
    public void bindSnapshot(EntityMaid maid, UUID presetId, String presetName,
                             List<ResourceLocation> order) {
        if (presetId == null) {
            return;
        }
        AutoWorkState current = getState(maid);
        setState(maid, current.withSnapshot(presetId,
                presetName == null ? "" : presetName, sanitizeOrder(order)));
    }

    /** True if the maid is currently managed by the auto work switch. */
    public boolean isEnabled(EntityMaid maid) {
        return getState(maid).enabled();
    }

    /** Returns the live {@link MinecraftServer} this service is bound to. */
    public MinecraftServer server() {
        return server;
    }

    /**
     * The order the scheduler should use: the baked snapshot when present,
     * otherwise the frozen seed preset referenced by the stored id.
     */
    public List<ResourceLocation> resolvedOrder(AutoWorkState state) {
        if (state.snapshotBaked()) {
            return state.order();
        }
        AutoWorkPreset seed = presetService() == null ? null : presetService().getPreset(state.presetId());
        if (seed == null) {
            seed = defaultPreset();
        }
        return seed == null ? List.of() : seed.getOrder();
    }

    // -----------------------------------------------------------------
    // Bound snapshot task edits (AI tool / future server-side callers)
    // -----------------------------------------------------------------

    public boolean addSnapshotTask(EntityMaid maid, ResourceLocation task) {
        if (task == null || isIdle(task)) {
            return false;
        }
        AutoWorkState state = ensureBoundSnapshot(maid);
        List<ResourceLocation> order = new ArrayList<>(state.order());
        if (order.contains(task) || order.size() >= MAX_SNAPSHOT_TASKS) {
            return false;
        }
        order.add(task);
        setState(maid, state.withOrder(order));
        return true;
    }

    public boolean removeSnapshotTask(EntityMaid maid, ResourceLocation task) {
        if (task == null) {
            return false;
        }
        AutoWorkState state = ensureBoundSnapshot(maid);
        List<ResourceLocation> order = new ArrayList<>(state.order());
        if (!order.remove(task)) {
            return false;
        }
        setState(maid, state.withOrder(order));
        return true;
    }

    public boolean moveSnapshotTask(EntityMaid maid, ResourceLocation task, int targetIndex) {
        if (task == null) {
            return false;
        }
        AutoWorkState state = ensureBoundSnapshot(maid);
        List<ResourceLocation> order = new ArrayList<>(state.order());
        int currentIndex = order.indexOf(task);
        if (currentIndex < 0) {
            return false;
        }
        int clamped = Math.max(0, Math.min(targetIndex, order.size() - 1));
        if (clamped == currentIndex) {
            return false;
        }
        order.remove(currentIndex);
        order.add(clamped, task);
        setState(maid, state.withOrder(order));
        return true;
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /** Drops malformed/duplicate/idle entries and caps the list length. */
    public static List<ResourceLocation> sanitizeOrder(List<ResourceLocation> order) {
        List<ResourceLocation> sanitized = new ArrayList<>();
        if (order == null) {
            return sanitized;
        }
        for (ResourceLocation task : order) {
            if (task == null || isIdle(task) || sanitized.contains(task)) {
                continue;
            }
            sanitized.add(task);
            if (sanitized.size() >= MAX_SNAPSHOT_TASKS) {
                break;
            }
        }
        return sanitized;
    }

    private static boolean isIdle(ResourceLocation task) {
        return TaskManager.getIdleTask().getUid().equals(task);
    }

    private AutoWorkState bakeFromSeed(AutoWorkState state) {
        AutoWorkPresetService presetService = presetService();
        if (presetService == null) {
            return null;
        }
        AutoWorkPreset seed = presetService.getPreset(state.presetId());
        if (seed == null) {
            seed = presetService.getDefaultPreset();
        }
        if (seed == null) {
            return null;
        }
        return state.withSnapshot(seed.getId(), seed.getName(), sanitizeOrder(seed.getOrder()));
    }

    private AutoWorkPresetService presetService() {
        return AutoWorkPresetService.getOrNull(server);
    }

    private AutoWorkPreset defaultPreset() {
        AutoWorkPresetService presetService = presetService();
        return presetService == null ? null : presetService.getDefaultPreset();
    }

    /**
     * Convenience guard: returns true if the maid is alive, owned by a
     * player, and the current level matches the bound server.
     */
    public static boolean isManagedByServer(EntityMaid maid, MinecraftServer server) {
        if (maid == null || server == null) {
            return false;
        }
        if (!maid.isAlive()) {
            return false;
        }
        LivingEntity owner = maid.getOwner();
        return owner == null || owner.level().getServer() == server;
    }
}
