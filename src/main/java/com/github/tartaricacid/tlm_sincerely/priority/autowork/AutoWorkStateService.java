package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-server facade for {@link AutoWorkState} reads/writes (T-2 A2).
 *
 * <p>State is persisted via TLM's {@code TaskDataRegister} (see
 * {@link AutoWorkTaskData}). All public methods must be called from the
 * server thread; they read/write the maid's task data map which is itself
 * a non-thread-safe structure.
 */
public final class AutoWorkStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkStateService.class);
    private static final Map<MinecraftServer, AutoWorkStateService> INSTANCES = new IdentityHashMap<>();

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
     * Reads the auto work state from the maid's TLM task data. Returns a
     * fresh disabled state with the current default preset id when no data
     * has been written yet.
     */
    public AutoWorkState getState(EntityMaid maid) {
        AutoWorkState stored = maid.getData(AutoWorkTaskDataKeys.STATE_KEY);
        if (stored != null) {
            return stored;
        }
        UUID fallbackPreset = defaultPresetId();
        return new AutoWorkState(false, fallbackPreset, 0);
    }

    /**
     * Persists the auto work state via the maid's TLM task data and marks
     * the data map dirty so the next entity tick syncs it to the client.
     */
    public void setState(EntityMaid maid, AutoWorkState state) {
        maid.setAndSyncData(AutoWorkTaskDataKeys.STATE_KEY, state);
    }

    public void setEnabled(EntityMaid maid, boolean enabled) {
        AutoWorkState current = getState(maid);
        if (current.enabled() == enabled) {
            return;
        }
        setState(maid, current.withEnabled(enabled));
    }

    public void setPresetId(EntityMaid maid, UUID presetId) {
        AutoWorkState current = getState(maid);
        if (current.presetId().equals(presetId)) {
            return;
        }
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(server);
        if (presetService != null && presetService.getPreset(presetId) == null) {
            return;
        }
        setState(maid, current.withPresetId(presetId));
    }

    /** True if the maid is currently managed by the auto work switch. */
    public boolean isEnabled(EntityMaid maid) {
        return getState(maid).enabled();
    }

    /** Returns the live {@link MinecraftServer} this service is bound to. */
    public MinecraftServer server() {
        return server;
    }

    private UUID defaultPresetId() {
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(server);
        if (presetService != null) {
            return presetService.getDefaultPresetId();
        }
        // Should not happen in normal flow: bind order is StateService ->
        // PresetService. Falling back to the IO helper guarantees a stable
        // UUID across cold-start reads.
        return AutoWorkPresetIO.LoadedLibrary.makeDefault().getId();
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
