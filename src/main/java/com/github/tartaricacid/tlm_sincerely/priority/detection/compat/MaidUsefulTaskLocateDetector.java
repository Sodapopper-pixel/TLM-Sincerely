package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Read-only detector for the maid_useful_task "locate" task
 * ({@code maid_useful_task:locate}), verified against the addon 1.4.0
 * bytecode (deobfuscated {@code MaidLocateTask.findTarget}).
 *
 * <p>The addon's real {@code findTarget} chain is never invoked: no
 * {@code ItemLocateEvent} is posted, no {@code MemoryUtil} cache entry is
 * written or cleared, and no item is equipped or moved. Only the two
 * {@code MemoryUtil} brain getters are read through reflection (they are
 * plain {@code getMemory().orElse(null)} lookups). The addon's else branch
 * ({@code CompatEntry.getLocateTarget}, Nature's Compass / Explorer's
 * Compass) is reproduced by reflecting the mod item fields and reading the
 * {@code FoundX}/{@code FoundZ} item NBT keys directly — the target is only
 * accepted when those keys are already recorded (contains check); any
 * reflection or NBT failure falls back conservatively to UNAVAILABLE.
 *
 * <p>Only the maid's main hand is ever considered; backpack contents can
 * never make this detector AVAILABLE. Preconditions before evaluating the
 * item branches: the task must be enabled, the maid must not be in home
 * mode, and the owner must exist within 3 blocks. Unknown main-hand items
 * and items without a derivable target resolve to UNAVAILABLE. AVAILABLE
 * always carries the derived {@code targetPos} plus an evidence tag.
 *
 * <p>Branch semantics match the addon bytecode:
 * <ul>
 *   <li>Ender eye: nearest stronghold via
 *   {@code findNearestMapStructure(EYE_OF_ENDER_LOCATED, pos, 100, false)}.</li>
 *   <li>Compass: lodestone compass reads {@code LodestonePos}/
 *   {@code LodestoneDimension} and requires the current dimension; a plain
 *   compass resolves to the shared spawn position (natural dimension only,
 *   same as {@code CompassItem.getSpawnPosition}).</li>
 *   <li>Beds ({@code ItemTags.BEDS}): owner respawn position when the owner
 *   is a {@code ServerPlayer} in the current dimension, falling back to the
 *   world shared spawn position.</li>
 *   <li>Filled map: requires non-null {@code MapItemSavedData}; starts at
 *   the map center (sea level), overridden by the first recorded banner
 *   (full position) and then by the first type-26 player decoration
 *   (x/z only), exactly like the addon.</li>
 * </ul>
 * The per-maid brain cache ({@code MemoryUtil.getCommonBlockCache}) is
 * honoured as the addon does: it is read first for the known item branches,
 * and treated as cleared when the main-hand item equals the recorded
 * {@code MemoryUtil.getLocateItem} entry (the addon clears it in that case).
 */
public final class MaidUsefulTaskLocateDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("maid_useful_task", "locate");

    private static final int EYE_OF_ENDER_SEARCH_RADIUS = 100;
    private static final int MAP_PLAYER_DECORATION_TYPE = 26;
    private static final double OWNER_DISTANCE_LIMIT = 3.0D;
    private static final String MAP_DECORATIONS_TAG = "Decorations";
    private static final String COMPASS_FOUND_X_TAG = "FoundX";
    private static final String COMPASS_FOUND_Z_TAG = "FoundZ";

    private static final Object REFLECT_LOCK = new Object();
    private static boolean memoryUtilResolved;
    private static Method memoryUtilGetLocateItem;
    private static Method memoryUtilGetCommonBlockCache;
    private static boolean natureCompassResolved;
    private static Field natureCompassItemField;
    private static boolean explorerCompassResolved;
    private static Field explorerCompassItemField;

    @Override
    public boolean supports(IMaidTask task) {
        return UID.equals(task.getUid());
    }

    @Override
    public int minIntervalTicks() {
        return 60;
    }

    @Override
    public DetectionResult detect(DetectionContext context, IMaidTask task) {
        EntityMaid maid = context.maid();
        ServerLevel level = context.level();

        if (!task.isEnable(maid)) {
            return unavailable(context, task, "TASK_DISABLED");
        }
        if (maid.isHomeModeEnable()) {
            return unavailable(context, task, "HOME_MODE_ACTIVE");
        }
        LivingEntity owner = maid.getOwner();
        if (owner == null || maid.distanceTo(owner) >= OWNER_DISTANCE_LIMIT) {
            return unavailable(context, task, "OWNER_NOT_NEAR");
        }
        ItemStack stack = maid.getMainHandItem();
        if (stack.isEmpty()) {
            return unavailable(context, task, "EMPTY_MAINHAND");
        }

        if (stack.is(Items.ENDER_EYE)) {
            BlockPos target = effectiveCache(maid, stack);
            if (target == null) {
                target = level.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED,
                        maid.blockPosition(), EYE_OF_ENDER_SEARCH_RADIUS, false);
            }
            if (target == null) {
                return unavailable(context, task, "EYE_NO_STRONGHOLD_IN_100");
            }
            return available(context, task, "EYE_OF_ENDER_LOCATED", target);
        }

        if (stack.is(Items.COMPASS)) {
            BlockPos target = effectiveCache(maid, stack);
            if (target == null) {
                CompoundTag tag = stack.getTag();
                GlobalPos globalPos = tag != null && CompassItem.isLodestoneCompass(stack)
                        ? CompassItem.getLodestonePosition(tag)
                        : CompassItem.getSpawnPosition(level);
                if (globalPos != null && level.dimension().equals(globalPos.dimension())) {
                    target = globalPos.pos();
                }
            }
            if (target == null) {
                return unavailable(context, task, "COMPASS_NO_TARGET");
            }
            return available(context, task, "COMPASS_TRACKED", target);
        }

        if (stack.is(ItemTags.BEDS)) {
            BlockPos target = effectiveCache(maid, stack);
            if (target == null && owner instanceof ServerPlayer player
                    && player.getRespawnDimension().equals(level.dimension())) {
                target = player.getRespawnPosition();
                if (target == null) {
                    GlobalPos spawn = CompassItem.getSpawnPosition(level);
                    if (spawn != null && level.dimension().equals(spawn.dimension())) {
                        target = spawn.pos();
                    }
                }
            }
            if (target == null) {
                return unavailable(context, task, "BED_NO_RESPAWN");
            }
            return available(context, task, "BED_RESPAWN_POS", target);
        }

        if (stack.is(Items.FILLED_MAP)) {
            BlockPos target = effectiveCache(maid, stack);
            if (target == null) {
                MapItemSavedData savedData = MapItem.getSavedData(stack, level);
                if (savedData != null) {
                    MutableBlockPos tmp = new MutableBlockPos(savedData.centerX,
                            level.getSeaLevel(), savedData.centerZ);
                    savedData.getBanners().stream().findFirst()
                            .ifPresent(banner -> tmp.set(banner.getPos().immutable()));
                    CompoundTag tag = stack.getTag();
                    if (tag != null) {
                        tag.getList(MAP_DECORATIONS_TAG, Tag.TAG_COMPOUND).stream()
                                .filter(dec -> dec instanceof CompoundTag
                                        && ((CompoundTag) dec).getByte("type") == MAP_PLAYER_DECORATION_TYPE)
                                .findFirst()
                                .ifPresent(dec -> {
                                    CompoundTag decoration = (CompoundTag) dec;
                                    tmp.setX(decoration.getInt("x"));
                                    tmp.setZ(decoration.getInt("z"));
                                });
                    }
                    target = tmp.immutable();
                }
            }
            if (target == null) {
                return unavailable(context, task, "MAP_NO_SAVED_DATA");
            }
            return available(context, task, "FILLED_MAP_CENTER", target);
        }

        // The addon's else branch (CompatEntry.getLocateTarget) never reads
        // the brain cache. Only Nature's / Explorer's Compass are honoured,
        // and only when their item NBT already records a target.
        BlockPos compatTarget = resolveCompatCompassTarget(level, stack);
        if (compatTarget != null) {
            return available(context, task, "COMPAT_COMPASS_TRACKED", compatTarget);
        }
        return unavailable(context, task, "UNKNOWN_ITEM_OR_NOT_TRACKED");
    }

    /**
     * Brain cache lookup exactly as the addon reads it: the cache is only
     * usable while the main-hand item differs from the recorded locate item,
     * because the addon clears the cache when both are non-empty and equal.
     * Reflection failures conservatively yield no cache.
     */
    private static BlockPos effectiveCache(EntityMaid maid, ItemStack current) {
        if (!resolveMemoryUtil()) {
            return null;
        }
        try {
            ItemStack last = (ItemStack) memoryUtilGetLocateItem.invoke(null, maid);
            if (!last.isEmpty() && !current.isEmpty() && ItemStack.isSameItemSameTags(last, current)) {
                return null;
            }
            return (BlockPos) memoryUtilGetCommonBlockCache.invoke(null, maid);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Resolves the two read-only {@code MemoryUtil} brain getters once.
     * Both come from the same class so a single resolved flag covers them.
     */
    private static boolean resolveMemoryUtil() {
        synchronized (REFLECT_LOCK) {
            if (memoryUtilResolved) {
                return memoryUtilGetLocateItem != null && memoryUtilGetCommonBlockCache != null;
            }
            memoryUtilResolved = true;
            try {
                Class<?> utilClass = Class.forName("studio.fantasyit.maid_useful_task.util.MemoryUtil");
                memoryUtilGetLocateItem = utilClass.getMethod("getLocateItem", EntityMaid.class);
                memoryUtilGetCommonBlockCache = utilClass.getMethod("getCommonBlockCache", EntityMaid.class);
            } catch (ClassNotFoundException | NoSuchMethodException exception) {
                memoryUtilGetLocateItem = null;
                memoryUtilGetCommonBlockCache = null;
            }
            return memoryUtilGetLocateItem != null && memoryUtilGetCommonBlockCache != null;
        }
    }

    private static BlockPos resolveCompatCompassTarget(ServerLevel level, ItemStack stack) {
        Item natureCompass = readNatureCompassItem();
        if (natureCompass != null && stack.is(natureCompass)) {
            BlockPos recorded = readRecordedCompassPos(stack);
            if (recorded != null) {
                return new BlockPos(recorded.getX(), level.getSeaLevel(), recorded.getZ());
            }
        }
        Item explorerCompass = readExplorerCompassItem();
        if (explorerCompass != null && stack.is(explorerCompass)) {
            BlockPos recorded = readRecordedCompassPos(stack);
            if (recorded != null) {
                return new BlockPos(recorded.getX(), level.getSeaLevel(), recorded.getZ());
            }
        }
        return null;
    }

    /**
     * Read-only confirmation that the compass item NBT already records a
     * found position (the addon's {@code getFoundBiomeX} /
     * {@code getFoundStructureX} simply {@code getInt} these keys). Without
     * both keys the state is INACTIVE/SEARCHING/NOT_FOUND → null.
     */
    private static BlockPos readRecordedCompassPos(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(COMPASS_FOUND_X_TAG) || !tag.contains(COMPASS_FOUND_Z_TAG)) {
            return null;
        }
        return new BlockPos(tag.getInt(COMPASS_FOUND_X_TAG), 0, tag.getInt(COMPASS_FOUND_Z_TAG));
    }

    private static Item readNatureCompassItem() {
        Field field = resolveNatureCompassItemField();
        if (field == null) {
            return null;
        }
        try {
            return (Item) field.get(null);
        } catch (IllegalAccessException | RuntimeException exception) {
            return null;
        }
    }

    private static Field resolveNatureCompassItemField() {
        synchronized (REFLECT_LOCK) {
            if (natureCompassResolved) {
                return natureCompassItemField;
            }
            natureCompassResolved = true;
            try {
                Class<?> clazz = Class.forName("com.chaosthedude.naturescompass.NaturesCompass");
                natureCompassItemField = clazz.getField("naturesCompass");
            } catch (ClassNotFoundException | NoSuchFieldException exception) {
                natureCompassItemField = null;
            }
            return natureCompassItemField;
        }
    }

    private static Item readExplorerCompassItem() {
        Field field = resolveExplorerCompassItemField();
        if (field == null) {
            return null;
        }
        try {
            return (Item) field.get(null);
        } catch (IllegalAccessException | RuntimeException exception) {
            return null;
        }
    }

    private static Field resolveExplorerCompassItemField() {
        synchronized (REFLECT_LOCK) {
            if (explorerCompassResolved) {
                return explorerCompassItemField;
            }
            explorerCompassResolved = true;
            try {
                Class<?> clazz = Class.forName("com.chaosthedude.explorerscompass.ExplorersCompass");
                explorerCompassItemField = clazz.getField("explorersCompass");
            } catch (ClassNotFoundException | NoSuchFieldException exception) {
                explorerCompassItemField = null;
            }
            return explorerCompassItemField;
        }
    }

    private static DetectionResult available(DetectionContext context, IMaidTask task,
                                             String evidence, BlockPos target) {
        return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                40, 0, evidence, target, null);
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                40, 0, evidence, null, null);
    }
}
