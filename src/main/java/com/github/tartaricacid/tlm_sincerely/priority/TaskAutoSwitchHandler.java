package com.github.tartaricacid.tlm_sincerely.priority;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber
public final class TaskAutoSwitchHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskAutoSwitchHandler.class);
    private static final int CHECK_INTERVAL = 20;
    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!PriorityConfig.ENABLED.get()) {
            return;
        }

        long currentTick = event.getServer().getTickCount();
        if (currentTick % CHECK_INTERVAL != 0) {
            return;
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
                if (currentTick - lastSwitch < cooldownTicks) {
                    continue;
                }

                IMaidTask bestTask = findBestTask(maid, sortedTasks);
                if (bestTask == null) {
                    continue;
                }

                IMaidTask currentTask = maid.getTask();
                if (bestTask == currentTask) {
                    // Maid is idle on the current task — try lower priority tasks
                    if (isMaidBrainIdle(maid)) {
                        IMaidTask fallback = findBestTaskSkipping(maid, sortedTasks, currentTask);
                        if (fallback != null && fallback != currentTask) {
                            maid.setTask(fallback);
                            cooldowns.put(maidId, currentTick);
                            LOGGER.info("[AutoSwitch] FALLBACK maid='{}' from '{}' to '{}'",
                                    maid.getName().getString(),
                                    currentTask != null ? currentTask.getUid() : "null",
                                    fallback.getUid());
                        }
                    }
                    continue;
                }

                maid.setTask(bestTask);
                cooldowns.put(maidId, currentTick);
                LOGGER.info("[AutoSwitch] SWITCH maid='{}' from '{}' to '{}'",
                        maid.getName().getString(),
                        currentTask != null ? currentTask.getUid() : "null",
                        bestTask.getUid());
            }
        }
    }

    private static IMaidTask findBestTask(EntityMaid maid, List<ResourceLocation> sortedTasks) {
        for (ResourceLocation taskId : sortedTasks) {
            IMaidTask task = TaskManager.findTask(taskId).orElse(null);
            if (task == null) {
                continue;
            }
            if (!task.isEnable(maid)) {
                continue;
            }
            return task;
        }
        return null;
    }

    /**
     * Check if the maid's brain is idle (no active work targets).
     * When idle on the current task, the auto-switch should consider
     * lower-priority tasks that might have work to do.
     */
    private static boolean isMaidBrainIdle(EntityMaid maid) {
        var brain = maid.getBrain();
        return brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT)
                && brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT)
                && brain.checkMemory(MemoryModuleType.PATH, MemoryStatus.VALUE_ABSENT)
                && brain.checkMemory(InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_ABSENT);
    }

    /**
     * Like findBestTask but skips a specific task (the current one).
     * Used when the maid is idle to find the next best available task.
     */
    private static IMaidTask findBestTaskSkipping(EntityMaid maid, List<ResourceLocation> sortedTasks, IMaidTask skipTask) {
        for (ResourceLocation taskId : sortedTasks) {
            IMaidTask task = TaskManager.findTask(taskId).orElse(null);
            if (task == null || task == skipTask) {
                continue;
            }
            if (!task.isEnable(maid)) {
                continue;
            }
            return task;
        }
        return null;
    }
}
