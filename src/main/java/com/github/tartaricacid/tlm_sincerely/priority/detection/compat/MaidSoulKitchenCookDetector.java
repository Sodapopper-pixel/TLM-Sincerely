package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskScanCursor;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * UID detector for the aggregate cook task {@code maidsoulkitchen:cook}.
 *
 * <p>Only the safe subset of MaidSoulKitchen 0.3.0.9 is implemented: the
 * {@code maidsoulkitchen:furnace} device family (vanilla
 * {@link AbstractFurnaceBlockEntity} — furnace / smoker / blast furnace).
 * The maid's selected device is resolved through a single cached reflection
 * point ({@link MaidSoulKitchenCookDetector.CookReflection}) that reads
 * {@code KitchenData.getCookName()} read-only; any reflection failure yields
 * {@code UNKNOWN}.
 *
 * <p>Work is judged with vanilla read-only APIs only:
 * {@link RecipeManager}, furnace slots and fuel ({@code ItemStack#getBurnTime}
 * / the LIT block state). A device that merely exists is never reported as
 * work — a cookable recipe, fuel and free result space are all required.
 * The addon cook state machine (MaidCookManager / CookMakeTask) is never
 * invoked and no item is ever inserted or extracted.
 *
 * <p>Scanning is incremental (ring scan through {@link TaskScanCursor}) under
 * the shared block budget, with Home/owner and path-budget constraints.
 *
 * <p>MaidSoulKitchen 1.21.1 (beta-0.1.4) degradation: {@code cook.v1.KitchenData}
 * (and its {@code getCookName()}) was removed — the new {@code CookData} only
 * carries whitelist/blacklist recipe rules — and the aggregate
 * {@code maidsoulkitchen:cook} task is no longer registered in
 * {@code TaskInfo} (devices became standalone tasks such as
 * {@code maidsoulkitchen:furnace}). On that version {@link #supports} therefore
 * stays false, and any legacy cook task yields {@code COOK_REFLEX_FAIL} (the
 * reflection target is gone, silently) — the detector conservatively reports
 * UNKNOWN / inactive instead of guessing a device from the new API.
 */
public final class MaidSoulKitchenCookDetector implements TaskWorkDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaidSoulKitchenCookDetector.class);
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("maidsoulkitchen", "cook");
    /** The only supported device; everything else is conservatively unsupported. */
    private static final ResourceLocation FURNACE_DEVICE = ResourceLocation.fromNamespaceAndPath("maidsoulkitchen", "furnace");
    /** Vanilla furnace slots: 0 = ingredient input, 1 = fuel, 2 = result. */
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_RESULT = 2;
    private static final int VERTICAL_RANGE = 2;

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
            return unavailable(context, task, "TASK_DISABLED");
        }
        CookReflection.ReflexResult kitchen = CookReflection.readCookName(task, maid);
        if (!kitchen.ok()) {
            return DetectionResult.unknown(task.getUid(), context.currentTick(), "COOK_REFLEX_FAIL");
        }
        ResourceLocation cookName = kitchen.cookName();
        if (cookName == null) {
            return unavailable(context, task, "COOK_NO_DEVICE_SELECTED");
        }
        if (!FURNACE_DEVICE.equals(cookName)) {
            // Conservative: the configured device is not part of the verified
            // safe subset, so this detector can never claim work for it.
            return unavailable(context, task, "COOK_DEVICE_UNSUPPORTED:" + cookName);
        }
        return scanFurnaceFamily(context, task);
    }

    private DetectionResult scanFurnaceFamily(DetectionContext context, IMaidTask task) {
        EntityMaid maid = context.maid();
        boolean homeMode = maid.isHomeModeEnable();
        BlockPos center = homeMode ? maid.getRestrictCenter() : maid.blockPosition().above();
        int horizontalRange = Math.max(0, (int) maid.getRestrictRadius() - 1);
        TaskScanCursor cursor = context.cursor();
        if (cursor == null || !cursor.matches(center, homeMode, horizontalRange, VERTICAL_RANGE)) {
            cursor = TaskScanCursor.start(center, homeMode, horizontalRange, VERTICAL_RANGE,
                    context.currentTick(), java.util.List.of());
        }
        int unreachableCandidates = cursor.unreachableCandidates();

        while (cursor != null && context.consumeBlock()) {
            BlockPos pos = cursor.currentPos();
            TaskScanCursor nextCursor = cursor.advance();

            if (!maid.isWithinRestriction(pos) || !isNearOwner(maid, pos)) {
                cursor = nextCursor;
                continue;
            }
            BlockEntity blockEntity = context.level().getBlockEntity(pos);
            if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace) || !furnaceWorkable(context, maid, furnace, pos)) {
                cursor = nextCursor;
                continue;
            }
            if (!context.consumePathCheck()) {
                context.setCursor(nextCursor);
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(pos)) {
                context.setCursor(null);
                return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                        40, 0, "FURNACE_WORKABLE", pos, null);
            }
            unreachableCandidates++;
            cursor = nextCursor == null ? null : nextCursor.withUnreachableCandidates(unreachableCandidates);
        }

        context.setCursor(cursor);
        if (cursor == null) {
            return unavailable(context, task,
                    unreachableCandidates > 0 ? "FULL_SCAN_ALL_UNREACHABLE" : "FULL_SCAN_NO_WORKABLE_FURNACE");
        }
        return DetectionResult.unknown(task.getUid(), context.currentTick(), "BLOCK_BUDGET_EXHAUSTED");
    }

    /**
     * Read-only workability of one furnace: a matching recipe must exist
     * (either in the input slot or in the maid inventory), fuel must be
     * present or burning, and the result slot must accept the output.
     */
    private static boolean furnaceWorkable(DetectionContext context, EntityMaid maid,
                                           AbstractFurnaceBlockEntity furnace, BlockPos pos) {
        RecipeType<? extends AbstractCookingRecipe> recipeType = recipeTypeOf(furnace);
        if (recipeType == null) {
            return false;
        }
        ServerLevel level = context.level();
        ItemStack input = furnace.getItem(SLOT_INPUT);
        ItemStack storedResult = furnace.getItem(SLOT_RESULT);
        if (!storedResult.isEmpty() && canStoreInMaid(maid, storedResult)) {
            return true;
        }
        if (input.isEmpty()) {
            return false;
        }
        // 1.21.1: the input must be a RecipeInput; the furnace input slot is a single
        // stack, which is exactly what SingleRecipeInput models. getRecipeFor now
        // wraps the match in RecipeHolder, hence the .value() unwrap. The wildcard
        // recipe type must be erased to AbstractCookingRecipe for inference — the
        // cast never executes at runtime (erasure), it only narrows the static type.
        @SuppressWarnings("unchecked")
        RecipeType<AbstractCookingRecipe> cookingType = (RecipeType<AbstractCookingRecipe>) recipeType;
        Optional<RecipeHolder<AbstractCookingRecipe>> recipe =
                level.getRecipeManager().getRecipeFor(cookingType, new SingleRecipeInput(input), level);
        if (recipe.isEmpty()) {
            return false;
        }
        AbstractCookingRecipe cooking = recipe.get().value();
        if (!storedResult.isEmpty()) {
            ItemStack output = cooking.getResultItem(level.registryAccess());
            if (!ItemStack.isSameItem(storedResult, output)
                    || storedResult.getCount() + output.getCount() > storedResult.getMaxStackSize()) {
                return false;
            }
        }
        return hasFuel(context, maid, furnace, pos, recipeType);
    }

    private static boolean hasFuel(DetectionContext context, EntityMaid maid,
                                   AbstractFurnaceBlockEntity furnace, BlockPos pos,
                                   RecipeType<? extends AbstractCookingRecipe> recipeType) {
        if (context.level().getBlockState(pos).hasProperty(BlockStateProperties.LIT)
                && context.level().getBlockState(pos).getValue(BlockStateProperties.LIT)) {
            return true;
        }
        ItemStack fuelStack = furnace.getItem(SLOT_FUEL);
        if (!fuelStack.isEmpty() && fuelStack.getBurnTime(recipeType) > 0) {
            return true;
        }
        IItemHandler inventory = maid.getAvailableInv(true);
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getBurnTime(recipeType) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean canStoreInMaid(EntityMaid maid, ItemStack stack) {
        return ItemHandlerHelper.insertItemStacked(maid.getAvailableInv(true), stack.copy(), true).isEmpty();
    }

    /** Maps the vanilla furnace family to its recipe type; unknown subtypes stay conservative. */
    private static RecipeType<? extends AbstractCookingRecipe> recipeTypeOf(AbstractFurnaceBlockEntity furnace) {
        if (furnace instanceof FurnaceBlockEntity) {
            return RecipeType.SMELTING;
        }
        if (furnace instanceof BlastFurnaceBlockEntity) {
            return RecipeType.BLASTING;
        }
        if (furnace instanceof SmokerBlockEntity) {
            return RecipeType.SMOKING;
        }
        return null;
    }

    private static boolean isNearOwner(EntityMaid maid, BlockPos pos) {
        if (maid.isHomeModeEnable()) {
            return true;
        }
        net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
        return owner != null && pos.closerToCenterThan(owner.position(), 8);
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                40, 0, evidence, null, null);
    }

    /**
     * Centralized read-only reflection into the maid's kitchen configuration.
     * Method handles are resolved once and cached; every failure (addon
     * version drift, missing data, linkage errors) surfaces as a non-ok
     * result so the caller returns {@code UNKNOWN}.
     */
    static final class CookReflection {
        private static final String IDATA_TASK_CLASS = "com.github.wallev.maidsoulkitchen.api.task.IDataTask";
        private static final String KITCHEN_DATA_CLASS =
                "com.github.wallev.maidsoulkitchen.entity.data.inner.task.cook.v1.KitchenData";
        private static final Method GET_COOK_DATA_KEY = findMethod(IDATA_TASK_CLASS, "getCookDataKey");
        private static final Method GET_COOK_NAME = findMethod(KITCHEN_DATA_CLASS, "getCookName");

        private CookReflection() {
        }

        private static Method findMethod(String className, String methodName) {
            try {
                return Class.forName(className).getMethod(methodName);
            } catch (ReflectiveOperationException | LinkageError error) {
                LOGGER.debug("[TaskDetect] cook reflection unavailable {}#{}: {}",
                        className, methodName, error.toString());
                return null;
            }
        }

        /**
         * Reads {@code KitchenData.getCookName()} through
         * {@code IDataTask.getCookDataKey()} + the read-only TLM
         * {@code EntityMaid.getData(TaskDataKey)} accessor (never
         * {@code getOrCreateData}, so nothing is created).
         */
        static ReflexResult readCookName(IMaidTask task, EntityMaid maid) {
            if (GET_COOK_DATA_KEY == null || GET_COOK_NAME == null) {
                return ReflexResult.failed();
            }
            try {
                Object key = GET_COOK_DATA_KEY.invoke(task);
                if (!(key instanceof TaskDataKey<?> taskDataKey)) {
                    return ReflexResult.failed();
                }
                Object kitchenData = maid.getData(taskDataKey);
                if (kitchenData == null) {
                    // No kitchen configuration at all: nothing to cook.
                    return ReflexResult.ok(null);
                }
                Object cookName = GET_COOK_NAME.invoke(kitchenData);
                if (cookName != null && !(cookName instanceof ResourceLocation)) {
                    return ReflexResult.failed();
                }
                return ReflexResult.ok((ResourceLocation) cookName);
            } catch (IllegalAccessException | InvocationTargetException | ClassCastException | LinkageError error) {
                LOGGER.debug("[TaskDetect] cook reflection read failed: {}", error.toString());
                return ReflexResult.failed();
            }
        }

        record ReflexResult(boolean ok, ResourceLocation cookName) {
            static ReflexResult ok(ResourceLocation cookName) {
                return new ReflexResult(true, cookName);
            }

            static ReflexResult failed() {
                return new ReflexResult(false, null);
            }
        }
    }
}
