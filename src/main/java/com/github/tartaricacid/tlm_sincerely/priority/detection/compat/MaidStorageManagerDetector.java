package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskScanCursor;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.RegistryObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only detector for the Maid Storage Manager "storage_manage" task
 * ({@code maid_storage_manager:storage_manage}).
 *
 * <p>Zero compile-time dependency on the addon. All MSM state is read through
 * a single central reflection point ({@link MsmReflect}): the schedule state
 * ({@code MemoryUtil.getCurrentlyWorking}), the pending-placement condition
 * ({@code Conditions.isNothingToPlace}), the placing/resorting target
 * memories (read directly from the maid's brain via
 * {@code MemoryModuleRegistry}, purely read-only), the request-list item and
 * the bound-storage baubles ({@code StorageDefineBauble.getStorages}).
 *
 * <p>Supported path: PLACE (or the RESORT fallback) — the maid carries
 * items to store and a usable storage block is nearby. A storage block means a
 * block in the addon's {@code maid_storage_manager:default_storage_blocks}
 * datapack tag (or an explicitly bound storage from a
 * {@code StorageDefineBauble}) that exposes an {@code ITEM_HANDLER}
 * capability, reachable via a budgeted path check. REQUEST/CO_WORK schedules
 * and unknown states return UNKNOWN. No addon search behavior is invoked, no
 * chest is opened and no item is ever extracted or inserted.
 *
 * <p>Scan ranges mirror the tree detector: home radius in home mode, 7 blocks
 * otherwise, ±7 vertical (cursor center y shifted up by one so the
 * {@link TaskScanCursor} offset scheme covers exactly {@code center.y ± 7}).
 * Every block state / block entity lookup and every path check is budgeted.
 */
public final class MaidStorageManagerDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("maid_storage_manager", "storage_manage");
    private static final int STORAGE_VERTICAL_RANGE = 7;
    private static final int NON_HOME_HORIZONTAL_RANGE = 7;
    private static final int MAX_BOUND_STORAGE_CHECKS = 16;
    private static final TagKey<Block> DEFAULT_STORAGE_TAG =
            TagKey.create(Registries.BLOCK, new ResourceLocation("maid_storage_manager", "default_storage_blocks"));

    @Override
    public boolean supports(IMaidTask task) {
        return UID.equals(task.getUid());
    }

    @Override
    public boolean usesBlockBudget() {
        return true;
    }

    @Override
    public DetectionResult detect(DetectionContext context, IMaidTask task) {
        EntityMaid maid = context.maid();
        if (!task.isEnable(maid)) {
            context.setCursor(null);
            return unavailable(context, task, "TASK_DISABLED");
        }

        MsmSnapshot snapshot = MsmReflect.snapshot(maid);
        String schedule = snapshot.schedule;
        if ("REQUEST".equals(schedule) || "CO_WORK".equals(schedule)) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), schedule + "_PATH_UNSUPPORTED");
        }

        Boolean pending = resolvePending(schedule, snapshot);
        if (pending == null) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "REFLECTION_GAP_UNKNOWN_STATE");
        }
        if (!pending) {
            if (schedule == null) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "REFLECTION_GAP_UNKNOWN_STATE");
            }
            if ("VIEW".equals(schedule)) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "VIEW_SCHEDULE_UNSUPPORTED");
            }
            return unavailable(context, task, "NO_ITEMS_TO_STORE");
        }

        Set<BlockPos> bound = new HashSet<>(MsmReflect.readBoundStorages(maid));

        // Active MSM target (placing or resorting memory) — validate it directly.
        Object activeTarget = snapshot.placingTarget != null ? snapshot.placingTarget : snapshot.resortingTarget;
        if (activeTarget != null) {
            BlockPos pos = MsmReflect.readStoragePos(activeTarget);
            if (pos != null && context.consumeBlock() && isUsableStorage(context, pos, bound.contains(pos))
                    && context.consumePathCheck() && maid.canPathReach(pos)) {
                context.setCursor(null);
                return available(context, task, pos, "ACTIVE_TARGET_STORAGE");
            }
        }

        // Explicitly bound storages (StorageDefineBauble), not limited to the
        // scan radius but capped by a fixed per-tick check budget.
        int boundChecked = 0;
        for (BlockPos pos : bound) {
            if (boundChecked++ >= MAX_BOUND_STORAGE_CHECKS) {
                break;
            }
            if (!context.consumeBlock()) {
                break;
            }
            if (!isUsableStorage(context, pos, true)) {
                continue;
            }
            if (!context.consumePathCheck()) {
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(pos)) {
                context.setCursor(null);
                return available(context, task, pos, "BOUND_STORAGE_REACHABLE");
            }
        }

        // Incremental scan over the default storage tag / bound positions.
        boolean homeMode = maid.isHomeModeEnable();
        BlockPos center = (homeMode ? maid.getRestrictCenter() : maid.blockPosition()).offset(0, 1, 0);
        int horizontalRange = homeMode ? Math.max(0, (int) maid.getRestrictRadius()) : NON_HOME_HORIZONTAL_RANGE;
        TaskScanCursor cursor = context.cursor();
        if (cursor == null || !cursor.matches(center, homeMode, horizontalRange, STORAGE_VERTICAL_RANGE)) {
            cursor = TaskScanCursor.start(center, homeMode, horizontalRange, STORAGE_VERTICAL_RANGE,
                    context.currentTick(), List.of());
        }
        int unreachableCandidates = cursor.unreachableCandidates();

        while (cursor != null && context.consumeBlock()) {
            BlockPos pos = cursor.currentPos();
            TaskScanCursor nextCursor = cursor.advance();

            if (!maid.isWithinRestriction(pos) || !isNearOwner(maid, pos)) {
                cursor = nextCursor;
                continue;
            }
            if (!isUsableStorage(context, pos, bound.contains(pos))) {
                cursor = nextCursor;
                continue;
            }
            if (!context.consumePathCheck()) {
                context.setCursor(nextCursor);
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(pos)) {
                context.setCursor(null);
                return available(context, task, pos, "STORAGE_BLOCK_NEARBY");
            }
            unreachableCandidates++;
            cursor = nextCursor == null ? null : nextCursor.withUnreachableCandidates(unreachableCandidates);
        }

        context.setCursor(cursor);
        if (cursor == null) {
            return unavailable(context, task,
                    unreachableCandidates > 0 ? "FULL_SCAN_ALL_UNREACHABLE" : "FULL_SCAN_NO_STORAGE");
        }
        return DetectionResult.unknown(task.getUid(), context.currentTick(), "BLOCK_BUDGET_EXHAUSTED");
    }

    /**
     * Decides whether the maid has items pending storage. With reflection
     * available this mirrors the addon's schedule decision; on reflection gaps
     * a conservative fallback inspects the backpack directly.
     *
     * @return true = pending, false = nothing to store, null = undeterminable
     */
    private static Boolean resolvePending(String schedule, MsmSnapshot snapshot) {
        if ("PLACE".equals(schedule)) {
            return true;
        }
        if (snapshot.nothingToPlace != null) {
            if ("RESORT".equals(schedule)) {
                return snapshot.resortingTarget != null || !snapshot.nothingToPlace;
            }
            return !snapshot.nothingToPlace;
        }
        return fallbackPending(snapshot.maid, snapshot.requestListItem, snapshot.requestListIsIgnored);
    }

    /**
     * Reflection-free approximation of the addon's pending-placement
     * condition. Ordinary items are pending; request-list items are pending
     * only when marked ignored, matching {@code Conditions.isNothingToPlace}.
     * When the request-list item or its
     * ignore flag cannot be read, the answer is undeterminable (null → the
     * caller reports UNKNOWN) instead of risking a false AVAILABLE.
     *
     * @return true = pending, false = nothing to store, null = undeterminable
     */
    private static Boolean fallbackPending(EntityMaid maid, Item requestListItem, Method requestListIsIgnored) {
        if (requestListItem == null || requestListIsIgnored == null) {
            return null;
        }
        IItemHandler inventory = maid.getAvailableInv(false);
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!stack.is(requestListItem)) {
                return true;
            }
            try {
                if ((boolean) requestListIsIgnored.invoke(null, stack)) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }
        return false;
    }

    /**
     * A usable storage is a block in the addon's default_storage_blocks tag
     * (or an explicitly bound position) whose block entity exposes an
     * ITEM_HANDLER capability. Nothing is opened or modified; the capability
     * is only checked for presence with no side.
     */
    private static boolean isUsableStorage(DetectionContext context, BlockPos pos, boolean bound) {
        ServerLevel level = context.level();
        BlockState state = level.getBlockState(pos);
        if (!state.is(DEFAULT_STORAGE_TAG) && !bound) {
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return false;
        }
        return blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent();
    }

    private static boolean isNearOwner(EntityMaid maid, BlockPos pos) {
        if (maid.isHomeModeEnable()) {
            return true;
        }
        LivingEntity owner = maid.getOwner();
        return owner != null && pos.closerToCenterThan(owner.position(), 8);
    }

    private static DetectionResult available(DetectionContext context, IMaidTask task, BlockPos target, String evidence) {
        return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                40, 0, evidence, target, null);
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                40, 0, evidence, null, null);
    }

    private record MsmSnapshot(EntityMaid maid, String schedule, Boolean nothingToPlace,
                               Object placingTarget, Object resortingTarget, Item requestListItem,
                               Method requestListIsIgnored) {
    }

    /**
     * Central reflection accessor for all Maid Storage Manager state. All
     * lookups are read-only: {@code getCurrentlyWorking} and
     * {@code isNothingToPlace} are pure reads, the placing/resorting memories
     * are read straight from the maid's brain through the registered memory
     * module types (never creating them), and bound storages are read from the
     * StorageDefineBauble NBT via its public static method. Any failure marks
     * the whole accessor unavailable and detectors fall back to their
     * conservative paths.
     */
    private static final class MsmReflect {
        private static final Object LOCK = new Object();
        private static volatile boolean resolved;
        private static volatile boolean available;
        private static Method getCurrentlyWorking;
        private static Method isNothingToPlace;
        private static Field placingInventoryField;
        private static Field resortingField;
        private static Method hasTarget;
        private static Method getTarget;
        private static Method storageGetPos;
        private static Field requestListItemField;
        private static Method requestListIsIgnored;
        private static Field storageDefineBaubleField;
        private static Method getStorages;

        private MsmReflect() {
        }

        static MsmSnapshot snapshot(EntityMaid maid) {
            resolve();
            if (!available) {
                return new MsmSnapshot(maid, null, null, null, null, null, null);
            }
            String schedule = null;
            Boolean nothingToPlace = null;
            Object placingTarget = null;
            Object resortingTarget = null;
            Item requestListItem = null;
            try {
                Object scheduleObj = getCurrentlyWorking.invoke(null, maid);
                schedule = scheduleObj == null ? null : scheduleObj.toString();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                schedule = null;
            }
            try {
                nothingToPlace = (boolean) isNothingToPlace.invoke(null, maid);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                nothingToPlace = null;
            }
            placingTarget = readTargetMemory(maid, placingInventoryField);
            resortingTarget = readTargetMemory(maid, resortingField);
            try {
                Object registryObject = requestListItemField.get(null);
                if (registryObject instanceof RegistryObject<?> registry) {
                    Object item = registry.get();
                    requestListItem = item instanceof Item ? (Item) item : null;
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                requestListItem = null;
            }
            return new MsmSnapshot(maid, schedule, nothingToPlace, placingTarget, resortingTarget,
                    requestListItem, requestListIsIgnored);
        }

        static List<BlockPos> readBoundStorages(EntityMaid maid) {
            resolve();
            if (!available) {
                return List.of();
            }
            try {
                Object baubleRegistry = storageDefineBaubleField.get(null);
                if (!(baubleRegistry instanceof RegistryObject<?> registry)) {
                    return List.of();
                }
                Object baubleItemObj = registry.get();
                if (!(baubleItemObj instanceof Item baubleItem)) {
                    return List.of();
                }
                IItemHandler baubleInventory = maid.getMaidBauble();
                List<BlockPos> positions = new ArrayList<>();
                for (int slot = 0; slot < baubleInventory.getSlots(); slot++) {
                    ItemStack stack = baubleInventory.getStackInSlot(slot);
                    if (stack.isEmpty() || !stack.is(baubleItem)) {
                        continue;
                    }
                    List<?> storages = (List<?>) getStorages.invoke(null, stack);
                    for (Object storage : storages) {
                        Object pos = storageGetPos.invoke(storage);
                        if (pos instanceof BlockPos blockPos) {
                            positions.add(blockPos.immutable());
                        }
                    }
                }
                return positions;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return List.of();
            }
        }

        static BlockPos readStoragePos(Object storage) {
            resolve();
            if (!available || storage == null) {
                return null;
            }
            try {
                Object pos = storageGetPos.invoke(storage);
                return pos instanceof BlockPos blockPos ? blockPos.immutable() : null;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }

        /**
         * Pure read of the target memory through the registered memory module
         * type; unlike {@code MemoryUtil.getPlacingInv/...} this never writes
         * a default value into the brain.
         */
        private static Object readTargetMemory(EntityMaid maid, Field moduleField) {
            try {
                Object moduleObject = moduleField.get(null);
                if (!(moduleObject instanceof RegistryObject<?> registry)) {
                    return null;
                }
                Object moduleType = registry.get();
                if (!(moduleType instanceof MemoryModuleType<?> memoryModuleType)) {
                    return null;
                }
                Optional<?> memory = maid.getBrain().getMemory(memoryModuleType);
                if (memory.isEmpty()) {
                    return null;
                }
                Object targetMemory = memory.get();
                if (!(boolean) hasTarget.invoke(targetMemory)) {
                    return null;
                }
                return getTarget.invoke(targetMemory);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }

        private static void resolve() {
            if (resolved) {
                return;
            }
            synchronized (LOCK) {
                if (resolved) {
                    return;
                }
                resolved = true;
                try {
                    Class<?> memoryUtil = Class.forName("studio.fantasyit.maid_storage_manager.util.MemoryUtil");
                    getCurrentlyWorking = memoryUtil.getMethod("getCurrentlyWorking", EntityMaid.class);
                    Class<?> conditions = Class.forName("studio.fantasyit.maid_storage_manager.util.Conditions");
                    isNothingToPlace = conditions.getMethod("isNothingToPlace", EntityMaid.class);
                    Class<?> memoryRegistry =
                            Class.forName("studio.fantasyit.maid_storage_manager.registry.MemoryModuleRegistry");
                    placingInventoryField = memoryRegistry.getField("PLACING_INVENTORY");
                    resortingField = memoryRegistry.getField("RESORTING");
                    Class<?> targetMemory =
                            Class.forName("studio.fantasyit.maid_storage_manager.maid.memory.AbstractTargetMemory");
                    hasTarget = targetMemory.getMethod("hasTarget");
                    getTarget = targetMemory.getMethod("getTarget");
                    Class<?> storage = Class.forName("studio.fantasyit.maid_storage_manager.storage.Storage");
                    storageGetPos = storage.getMethod("getPos");
                    Class<?> itemRegistry =
                            Class.forName("studio.fantasyit.maid_storage_manager.registry.ItemRegistry");
                    requestListItemField = itemRegistry.getField("REQUEST_LIST_ITEM");
                    storageDefineBaubleField = itemRegistry.getField("STORAGE_DEFINE_BAUBLE");
                    Class<?> requestList =
                            Class.forName("studio.fantasyit.maid_storage_manager.items.RequestListItem");
                    requestListIsIgnored = requestList.getMethod("isIgnored", ItemStack.class);
                    Class<?> defineBauble =
                            Class.forName("studio.fantasyit.maid_storage_manager.items.StorageDefineBauble");
                    getStorages = defineBauble.getMethod("getStorages", ItemStack.class);
                    available = true;
                } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException exception) {
                    available = false;
                }
            }
        }
    }
}
