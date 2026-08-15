package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

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
            TaskWorkDetectorRegistry.register(MaidUsefulTaskLocateDetector.UID, new MaidUsefulTaskLocateDetector());
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
