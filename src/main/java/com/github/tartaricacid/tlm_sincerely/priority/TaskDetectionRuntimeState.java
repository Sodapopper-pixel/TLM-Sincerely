package com.github.tartaricacid.tlm_sincerely.priority;

import com.github.tartaricacid.tlm_sincerely.priority.decision.MaidSwitchState;
import com.github.tartaricacid.tlm_sincerely.priority.detection.MaidDetectionCache;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskDetectionScheduler;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** State is scoped to a server instance so integrated-server ticks never leak into a new world. */
public final class TaskDetectionRuntimeState {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDetectionRuntimeState.class);
    private static final long UNLOADED_MAID_GRACE_TICKS = 1200;
    private static final Map<MinecraftServer, TaskDetectionRuntimeState> STATES = new IdentityHashMap<>();

    private final Map<UUID, MaidDetectionCache> detectionCaches = new HashMap<>();
    private final Map<UUID, MaidSwitchState> switchStates = new HashMap<>();
    private final Map<UUID, Long> lastSeenTicks = new HashMap<>();
    private final TaskDetectionScheduler scheduler = new TaskDetectionScheduler();
    private long lastTick = -1;

    private TaskDetectionRuntimeState() {
    }

    public static synchronized void start(MinecraftServer server) {
        STATES.put(server, new TaskDetectionRuntimeState());
    }

    public static synchronized TaskDetectionRuntimeState get(MinecraftServer server) {
        return STATES.computeIfAbsent(server, unused -> new TaskDetectionRuntimeState());
    }

    public static synchronized void stop(MinecraftServer server) {
        TaskDetectionRuntimeState state = STATES.remove(server);
        if (state != null) {
            state.clearAll();
            LOGGER.debug("[TaskState] reset reason=SERVER_STOPPED");
        }
    }

    public static synchronized void clearMaid(MinecraftServer server, UUID maidUuid) {
        TaskDetectionRuntimeState state = STATES.get(server);
        if (state != null) {
            state.clearMaid(maidUuid);
        }
    }

    public MaidDetectionCache getDetectionCache(EntityMaid maid) {
        return detectionCaches.computeIfAbsent(maid.getUUID(), unused -> new MaidDetectionCache());
    }

    public MaidSwitchState getSwitchState(EntityMaid maid) {
        return switchStates.computeIfAbsent(maid.getUUID(), unused -> new MaidSwitchState());
    }

    public TaskDetectionScheduler scheduler() {
        return scheduler;
    }

    public void beginTick(long currentTick, Set<UUID> loadedMaidIds) {
        if (lastTick >= 0 && currentTick < lastTick) {
            LOGGER.warn("[TaskState] tick moved backwards from {} to {}, resetting state", lastTick, currentTick);
            clearAll();
        }
        lastTick = currentTick;
        for (UUID maidUuid : loadedMaidIds) {
            lastSeenTicks.put(maidUuid, currentTick);
        }
        Set<UUID> stale = new HashSet<>();
        for (Map.Entry<UUID, Long> entry : lastSeenTicks.entrySet()) {
            if (!loadedMaidIds.contains(entry.getKey()) && currentTick >= entry.getValue()
                    && currentTick - entry.getValue() > UNLOADED_MAID_GRACE_TICKS) {
                stale.add(entry.getKey());
            }
        }
        for (UUID maidUuid : stale) {
            clearMaid(maidUuid);
        }
    }

    private void clearMaid(UUID maidUuid) {
        MaidDetectionCache cache = detectionCaches.remove(maidUuid);
        if (cache != null) {
            cache.clear();
        }
        switchStates.remove(maidUuid);
        lastSeenTicks.remove(maidUuid);
    }

    private void clearAll() {
        detectionCaches.values().forEach(MaidDetectionCache::clear);
        detectionCaches.clear();
        switchStates.clear();
        lastSeenTicks.clear();
        lastTick = -1;
    }
}
