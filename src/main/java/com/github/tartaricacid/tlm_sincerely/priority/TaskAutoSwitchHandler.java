package com.github.tartaricacid.tlm_sincerely.priority;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber
public final class TaskAutoSwitchHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskAutoSwitchHandler.class);
    private static final int CHECK_INTERVAL = 20;
    private static final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final Map<UUID, Long> preemptLastTick = new HashMap<>();
    private static boolean configLogged = false;

    // Probe state per maid
    private static final Map<UUID, Integer> probeIndex = new HashMap<>();
    private static final Map<UUID, Long> probeStartTick = new HashMap<>();
    private static final Map<UUID, Long> probeRoundEndTick = new HashMap<>();
    private static final Map<UUID, Set<ResourceLocation>> probeExcluded = new HashMap<>();

    // Idle grace tracking
    private static final Map<UUID, Long> lastNonIdleTick = new HashMap<>();

    // Diagnostic: per-tick brain idle transition tracking
    private static final Map<UUID, Boolean> lastIdleState = new HashMap<>();
    private static final Map<UUID, Long> lastFlipTick = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!PriorityConfig.ENABLED.get()) {
            return;
        }

        long currentTick = event.getServer().getTickCount();
        boolean decisionTick = currentTick % CHECK_INTERVAL == 0;

        // Per-tick diagnostic sampling (no behavior change): record BUSY<->IDLE flips
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof EntityMaid maid)) continue;
                if (!maid.isAlive()) continue;
                if (maid.getScheduleDetail() != Activity.WORK) continue;

                UUID maidId = maid.getUUID();
                boolean idleNow = isBrainIdle(maid);
                Boolean prev = lastIdleState.put(maidId, idleNow);
                if (prev == null) {
                    lastFlipTick.put(maidId, currentTick);
                } else if (prev != idleNow) {
                    long since = lastFlipTick.getOrDefault(maidId, currentTick);
                    IMaidTask t = maid.getTask();
                    LOGGER.info("[AutoSwitch] BRAIN_FLIP tick={} maid='{}' task='{}' {}->{} lasted={} brain=[{}]",
                            currentTick, maid.getName().getString(),
                            t != null ? t.getUid() : "?",
                            prev ? "IDLE" : "BUSY", idleNow ? "IDLE" : "BUSY",
                            currentTick - since, brainDump(maid.getBrain()));
                    lastFlipTick.put(maidId, currentTick);
                }
            }
        }

        if (decisionTick && !configLogged) {
            LOGGER.info("[AutoSwitch] CONFIG enabled={} pollInterval={} attackPreempt={} probeGrace={} probeWait={} probeCooldown={}",
                    PriorityConfig.ENABLED.get(), PriorityConfig.COOLDOWN.get(),
                    PriorityConfig.ATTACK_PREEMPT.get(), PriorityConfig.PROBE_GRACE_TICKS.get(),
                    PriorityConfig.PROBE_WAIT_TICKS.get(), PriorityConfig.PROBE_COOLDOWN.get());
            configLogged = true;
        }

        TaskPriorityPreset preset = TaskPriorityManager.getActivePreset();
        if (preset == null) {
            LOGGER.warn("[AutoSwitch] No active preset found, skip");
            return;
        }

        List<ResourceLocation> sortedTasks = new ArrayList<>(preset.getSortedTasks());
        if (sortedTasks.isEmpty()) {
            LOGGER.warn("[AutoSwitch] Sorted tasks list is empty, skip");
            return;
        }
        sortedTasks.sort(Comparator.comparingInt((ResourceLocation id) ->
                preset.getPriorities().getOrDefault(id, 10)));

        int cooldownTicks = PriorityConfig.COOLDOWN.get();

        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof EntityMaid maid)) continue;
                if (!maid.isAlive()) continue;
                if (maid.getScheduleDetail() != Activity.WORK) continue;

                UUID maidId = maid.getUUID();
                long lastSwitch = cooldowns.getOrDefault(maidId, 0L);

                boolean probing = probeStartTick.containsKey(maidId);

                // Attack preemption only runs outside an active probe round.
                // Otherwise it can interrupt a candidate before PROBE_CHECK.
                if (decisionTick && !probing && PriorityConfig.ATTACK_PREEMPT.get() &&
                        currentTick - lastSwitch >= cooldownTicks) {
                    long lastPreempt = preemptLastTick.getOrDefault(maidId, 0L);
                    Long roundEnd = probeRoundEndTick.get(maidId);
                    boolean inProbeCooldown = roundEnd != null &&
                            currentTick - roundEnd < PriorityConfig.PROBE_COOLDOWN.get();
                    if (!inProbeCooldown && currentTick - lastPreempt >= PriorityConfig.PROBE_COOLDOWN.get()) {
                        if (handleAttackPreempt(maid, currentTick, sortedTasks)) {
                            preemptLastTick.put(maidId, currentTick);
                            continue;
                        }
                    }
                }

                // Probe state machine
                if (probing) {
                    handleProbeStep(maid, currentTick, sortedTasks);
                } else if (!decisionTick) {
                    continue;
                } else if (currentTick - lastSwitch >= cooldownTicks) {
                    handleNormalState(maid, currentTick, sortedTasks);
                } else {
                    if (!isBrainIdle(maid)) {
                        lastNonIdleTick.put(maidId, currentTick);
                    }
                    LOGGER.info("[AutoSwitch] BLOCKED tick={} maid='{}' cooldownRemaining={} lastSwitch={} cooldown={}",
                            currentTick, maid.getName().getString(),
                            cooldownTicks - (currentTick - lastSwitch), lastSwitch, cooldownTicks);
                }
            }
        }
    }

    // -------------------------------------------------------
    // Attack preemption
    // -------------------------------------------------------

    private static boolean handleAttackPreempt(EntityMaid maid, long currentTick, List<ResourceLocation> sortedTasks) {
        IMaidTask currentTask = maid.getTask();
        for (ResourceLocation taskId : sortedTasks) {
            IMaidTask task = TaskManager.findTask(taskId).orElse(null);
            if (task == null) continue;
            if (!task.isEnable(maid)) continue;
            if (task == currentTask) break;

            if (task instanceof IAttackTask) {
                boolean hasTarget = IAttackTask.findFirstValidAttackTarget(maid).isPresent();
                if (hasTarget) {
                    UUID maidId = maid.getUUID();
                    probeIndex.remove(maidId);
                    probeStartTick.remove(maidId);
                    // Keep probeExcluded so we don't re-probe already-excluded tasks
                    lastNonIdleTick.put(maidId, currentTick);
                    maid.setTask(task);
                    cooldowns.put(maidId, currentTick);
                    LOGGER.info("[AutoSwitch] PREEMPT maid='{}' from='{}' to='{}' targets=YES",
                            maid.getName().getString(),
                            currentTask != null ? currentTask.getUid() : "null",
                            task.getUid());
                    return true;
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------
    // Normal state — check if idle and start probe
    // -------------------------------------------------------

    private static void handleNormalState(EntityMaid maid, long currentTick, List<ResourceLocation> sortedTasks) {
        UUID maidId = maid.getUUID();
        IMaidTask currentTask = maid.getTask();
        boolean idle = isBrainIdle(maid);
        String brainState = brainDump(maid.getBrain());
        int grace = PriorityConfig.PROBE_GRACE_TICKS.get();

        long lastBusy = lastNonIdleTick.getOrDefault(maidId, 0L);
        long idleDuration = currentTick - lastBusy;

        // Log idle check every interval
        LOGGER.info("[AutoSwitch] NORMAL tick={} maid='{}' task='{}' idle={} brain=[{}] idleFor={} grace={} lastBusy={}",
                currentTick, maid.getName().getString(),
                currentTask != null ? currentTask.getUid() : "?",
                idle, brainState, idleDuration, grace, lastBusy);

        if (!idle) {
            lastNonIdleTick.put(maidId, currentTick);
            return;
        }

        if (idleDuration < grace) {
            LOGGER.info("[AutoSwitch] NORMAL_SKIP tick={} idleFor={} < grace={}",
                    currentTick, idleDuration, grace);
            return;
        }

        // Check if in probe cooldown
        Long roundEnd = probeRoundEndTick.get(maidId);
        if (roundEnd != null) {
            long cooldownLeft = PriorityConfig.PROBE_COOLDOWN.get() - (currentTick - roundEnd);
            if (cooldownLeft > 0) {
                LOGGER.info("[AutoSwitch] NORMAL_COOLDOWN tick={} cooldownLeft={} probeCooldown={} roundEnd={}",
                        currentTick, cooldownLeft, PriorityConfig.PROBE_COOLDOWN.get(), roundEnd);
                return;
            }
        }

        LOGGER.info("[AutoSwitch] NORMAL_TRIGGER tick={} idleFor={} grace={} roundEnd={} probeCooldown={}",
                currentTick, idleDuration, grace, roundEnd, PriorityConfig.PROBE_COOLDOWN.get());

        LOGGER.info("[AutoSwitch] PROBE_TRIGGER tick={} maid='{}' task='{}' idleFor={} grace={} brain=[{}]",
                currentTick, maid.getName().getString(),
                currentTask != null ? currentTask.getUid() : "?",
                idleDuration, grace, brainState);

        startProbeRound(maid, currentTick, sortedTasks);
    }

    // -------------------------------------------------------
    // Probe round handling
    // -------------------------------------------------------

    private static void startProbeRound(EntityMaid maid, long currentTick, List<ResourceLocation> sortedTasks) {
        UUID maidId = maid.getUUID();
        IMaidTask currentTask = maid.getTask();

        Set<ResourceLocation> excluded = new HashSet<>();
        if (currentTask != null) {
            excluded.add(currentTask.getUid());
        }
        probeExcluded.put(maidId, excluded);
        probeRoundEndTick.remove(maidId);

        // Find first non-excluded, enabled task
        int startIndex = findNextProbeTarget(maid, sortedTasks, excluded, 0);
        if (startIndex < 0) {
            finishProbeRound(maid, currentTick);
            return;
        }

        probeIndex.put(maidId, startIndex);
        probeStartTick.put(maidId, currentTick);

        ResourceLocation targetId = sortedTasks.get(startIndex);
        IMaidTask targetTask = TaskManager.findTask(targetId).orElse(null);
        if (targetTask != null) {
            maid.setTask(targetTask);
            clearTaskMemories(maid);
            LOGGER.info("[AutoSwitch] PROBE_START maid='{}' trying '{}'",
                    maid.getName().getString(), targetId);
        }
    }

    private static void handleProbeStep(EntityMaid maid, long currentTick, List<ResourceLocation> sortedTasks) {
        UUID maidId = maid.getUUID();
        long startTick = probeStartTick.get(maidId);
        long waited = currentTick - startTick;
        int needed = PriorityConfig.PROBE_WAIT_TICKS.get();

        if (waited < needed) {
            return;
        }

        // Read brain result
        IMaidTask probeTask = maid.getTask();
        boolean idle = isBrainIdle(maid);
        String brainState = brainDump(maid.getBrain());

        LOGGER.info("[AutoSwitch] PROBE_CHECK tick={} maid='{}' probeTask='{}' waited={} idle={} brain=[{}]",
                currentTick, maid.getName().getString(),
                probeTask != null ? probeTask.getUid() : "?",
                waited, idle, brainState);

        if (!idle) {
            LOGGER.info("[AutoSwitch] PROBE_FOUND maid='{}' task='{}' has work",
                    maid.getName().getString(),
                    probeTask != null ? probeTask.getUid() : "?");
            clearProbeState(maidId);
            lastNonIdleTick.put(maidId, currentTick);
            cooldowns.put(maidId, currentTick);
            return;
        }

        // Idle — mark excluded and try next
        if (probeTask != null) {
            probeExcluded.get(maidId).add(probeTask.getUid());
            LOGGER.info("[AutoSwitch] PROBE_IDLE maid='{}' task='{}' excluded ({} remain)",
                    maid.getName().getString(), probeTask.getUid(),
                    sortedTasks.size() - probeExcluded.get(maidId).size());
        }

        int currentIdx = probeIndex.get(maidId);
        int nextIdx = findNextProbeTarget(maid, sortedTasks, probeExcluded.get(maidId), currentIdx + 1);

        if (nextIdx < 0) {
            finishProbeRound(maid, currentTick);
            return;
        }

        probeIndex.put(maidId, nextIdx);
        probeStartTick.put(maidId, currentTick);

        ResourceLocation targetId = sortedTasks.get(nextIdx);
        IMaidTask targetTask = TaskManager.findTask(targetId).orElse(null);
        if (targetTask != null) {
            maid.setTask(targetTask);
            clearTaskMemories(maid);
            LOGGER.info("[AutoSwitch] PROBE_TRY maid='{}' index={} trying '{}'",
                    maid.getName().getString(), nextIdx, targetId);
        }
    }

    private static int findNextProbeTarget(EntityMaid maid, List<ResourceLocation> sortedTasks,
                                            Set<ResourceLocation> excluded, int startFrom) {
        for (int i = startFrom; i < sortedTasks.size(); i++) {
            ResourceLocation taskId = sortedTasks.get(i);
            if (excluded.contains(taskId)) continue;
            IMaidTask task = TaskManager.findTask(taskId).orElse(null);
            if (task == null) continue;
            if (!task.isEnable(maid)) {
                excluded.add(taskId); // can't use this task at all
                continue;
            }
            return i;
        }
        return -1;
    }

    private static void finishProbeRound(EntityMaid maid, long currentTick) {
        UUID maidId = maid.getUUID();
        probeRoundEndTick.put(maidId, currentTick);
        lastNonIdleTick.put(maidId, currentTick);
        clearProbeState(maidId);
        LOGGER.info("[AutoSwitch] PROBE_DONE maid='{}' all tasks idle, cooldown {} ticks",
                maid.getName().getString(), PriorityConfig.PROBE_COOLDOWN.get());
    }

    private static void clearProbeState(UUID maidId) {
        probeIndex.remove(maidId);
        probeStartTick.remove(maidId);
        probeExcluded.remove(maidId);
    }

    // -------------------------------------------------------
    // Brain idle check
    // -------------------------------------------------------

    private static boolean isBrainIdle(EntityMaid maid) {
        var brain = maid.getBrain();
        return brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT)
                && brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT)
                && brain.checkMemory(MemoryModuleType.PATH, MemoryStatus.VALUE_ABSENT)
                && brain.checkMemory(InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_ABSENT);
    }

    private static void clearTaskMemories(EntityMaid maid) {
        var brain = maid.getBrain();
        String before = brainDump(brain);
        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.eraseMemory(MemoryModuleType.PATH);
        brain.eraseMemory(InitEntities.TARGET_POS.get());
        LOGGER.info("[AutoSwitch] CLEAR_MEMORIES maid='{}' before=[{}] after=[{}]",
                maid.getName().getString(), before, brainDump(brain));
    }

    private static String brainDump(Brain<?> brain) {
        return String.format("ATK:%s WLK:%s PATH:%s TGT:%s",
                mem(brain, MemoryModuleType.ATTACK_TARGET),
                mem(brain, MemoryModuleType.WALK_TARGET),
                mem(brain, MemoryModuleType.PATH),
                mem(brain, InitEntities.TARGET_POS.get()));
    }

    private static String mem(Brain<?> brain, MemoryModuleType<?> type) {
        return brain.checkMemory(type, MemoryStatus.VALUE_PRESENT) ? "1" : "0";
    }
}
