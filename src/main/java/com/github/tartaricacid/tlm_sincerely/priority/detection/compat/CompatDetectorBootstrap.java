package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.AttackTaskWorkDetector;
import com.github.tartaricacid.tlm_sincerely.priority.detection.MaidHardToolService;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetectorRegistry;
import net.minecraftforge.fml.ModList;

/** Registers only detectors whose task semantics were verified against installed jars. */
public final class CompatDetectorBootstrap {
    private static boolean registered;

    private CompatDetectorBootstrap() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        TaskWorkDetectorRegistry.register(BuiltinHoneyDetector.UID, new BuiltinHoneyDetector());
        TaskWorkDetectorRegistry.register(BuiltinMilkDetector.UID, new BuiltinMilkDetector());
        TaskWorkDetectorRegistry.register(BuiltinFeedOwnerDetector.UID, new BuiltinFeedOwnerDetector());
        TaskWorkDetectorRegistry.register(BuiltinFeedAnimalDetector.UID, new BuiltinFeedAnimalDetector());
        TaskWorkDetectorRegistry.register(BuiltinTorchDetector.UID, new BuiltinTorchDetector());
        TaskWorkDetectorRegistry.register(BuiltinFishingDetector.UID, new BuiltinFishingDetector());
        TaskWorkDetectorRegistry.register(BuiltinExtinguishingDetector.UID, new BuiltinExtinguishingDetector());
        TaskWorkDetectorRegistry.register(BuiltinShearsDetector.UID, new BuiltinShearsDetector());
        // 硬性工具需求注册：检测器允许背包持有，切换前由 MaidHardToolService
        // 装备到主手。honey（双分支非单一强制）不注册，保持原有语义。
        MaidHardToolService.register(BuiltinFishingDetector.UID, BuiltinFishingDetector.REQUIRED_TOOL);
        MaidHardToolService.register(BuiltinExtinguishingDetector.UID, BuiltinExtinguishingDetector.REQUIRED_TOOL);
        MaidHardToolService.register(BuiltinShearsDetector.UID, BuiltinShearsDetector.REQUIRED_TOOL);
        // 攻击族硬性武器：检测器允许背包持有，切换前由 MaidHardToolService 换到主手。
        MaidHardToolService.register(AttackTaskWorkDetector.UID_ATTACK, AttackTaskWorkDetector.MELEE_WEAPON);
        MaidHardToolService.register(AttackTaskWorkDetector.UID_RANGED, AttackTaskWorkDetector.BOW_WEAPON);
        MaidHardToolService.register(AttackTaskWorkDetector.UID_CROSSBOW, AttackTaskWorkDetector.CROSSBOW_WEAPON);
        MaidHardToolService.register(AttackTaskWorkDetector.UID_TRIDENT, AttackTaskWorkDetector.TRIDENT_WEAPON);
        MaidHardToolService.register(AttackTaskWorkDetector.UID_DANMAKU, AttackTaskWorkDetector.DANMAKU_WEAPON);
        TaskWorkDetectorRegistry.register(BuiltinBoardGamesDetector.UID, new BuiltinBoardGamesDetector());
        if (ModList.get().isLoaded("maidsoulkitchen")) {
            if (isClassPresent("com.github.wallev.maidsoulkitchen.api.task.farm.ICompatFarmTask")
                    && isClassPresent("com.github.wallev.maidsoulkitchen.api.task.farm.ICompatFarmHandler")
                    && isClassPresent("com.github.wallev.maidsoulkitchen.entity.data.inner.task.berryfruit.v1.BerryFruitData")) {
                TaskWorkDetectorRegistry.register(MaidSoulKitchenBerryDetector.UID,
                        new MaidSoulKitchenBerryDetector());
                TaskWorkDetectorRegistry.register(MaidSoulKitchenFruitDetector.UID,
                        new MaidSoulKitchenFruitDetector());
            }
            TaskWorkDetectorRegistry.register(MaidSoulKitchenFeedAnimalDetector.UID,
                    new MaidSoulKitchenFeedAnimalDetector());
            TaskWorkDetectorRegistry.register(MaidSoulKitchenCookDetector.UID, new MaidSoulKitchenCookDetector());
        }
        if (ModList.get().isLoaded("maid_useful_task")) {
            TaskWorkDetectorRegistry.register(MaidUsefulTaskTreeDetector.UID, new MaidUsefulTaskTreeDetector());
            // maid_useful_task:locate 不注册 Detector：该任务要求非 home + 主人在 3 格内 +
            // 主手持定位物，本质是跟随主人的寻路任务，不适用自动切换（见 docs/自动切换工作模块.md）。
        }
        if (ModList.get().isLoaded("maid_storage_manager")) {
            TaskWorkDetectorRegistry.register(MaidStorageManagerDetector.UID, new MaidStorageManagerDetector());
        }
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, CompatDetectorBootstrap.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }
}
