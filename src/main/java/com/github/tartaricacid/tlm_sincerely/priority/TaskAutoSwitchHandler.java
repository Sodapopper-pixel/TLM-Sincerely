package com.github.tartaricacid.tlm_sincerely.priority;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber
public final class TaskAutoSwitchHandler {
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
            return;
        }

        List<ResourceLocation> sortedTasks = new ArrayList<>(preset.getSortedTasks());
        if (sortedTasks.isEmpty()) {
            return;
        }
        sortedTasks.sort(Comparator.comparingInt((ResourceLocation id) ->
                preset.getPriorities().getOrDefault(id, 10)));

        int cooldownTicks = PriorityConfig.COOLDOWN.get();

        for (ServerLevel level : event.getServer().getAllLevels()) {

            for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class,
                    new AABB(level.getSharedSpawnPos()).inflate(256),
                    e -> e.isAlive() && e.getScheduleDetail() == Activity.WORK)) {
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
                    continue;
                }

                maid.setTask(bestTask);
                cooldowns.put(maidId, currentTick);
            }
        }
    }

    private static IMaidTask findBestTask(EntityMaid maid, List<ResourceLocation> sortedTasks) {
        for (ResourceLocation taskId : sortedTasks) {
            IMaidTask task = TaskManager.findTask(taskId).orElse(null);
            if (task == null) {
                continue;
            }
            if (task.isEnable(maid)) {
                return task;
            }
        }
        return null;
    }
}
