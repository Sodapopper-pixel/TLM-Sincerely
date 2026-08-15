package com.github.tartaricacid.tlm_sincerely.priority.detection.compat;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionContext;
import com.github.tartaricacid.tlm_sincerely.priority.detection.DetectionResult;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskScanCursor;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetector;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Read-only detector for the maid_useful_task "maid_tree" task
 * ({@code maid_useful_task:maid_tree}).
 *
 * <p>Zero compile-time dependency on the addon: the task is matched by its UID
 * and the per-maid {@code skipNonNature} toggle is read through one
 * reflection point ({@code MaidLoggingConfig.get(maid).skipNonNature()}).
 * When reflection fails the detector conservatively assumes
 * {@code skipNonNature = true}, i.e. only natural trees are accepted.
 *
 * <p>Scan ranges match the TLM home/non-home search used by the addon's
 * find-target walk: home radius when home mode is on, otherwise 7 blocks
 * horizontally, ±7 vertically. The cursor center y is shifted up by one so the
 * {@link TaskScanCursor} vertical offset scheme {@code [-range-1, +range-1]}
 * covers exactly {@code center.y ± 7}.
 *
 * <p>The scan candidate is the addon's stand position ({@code pos}): the maid
 * stands there and the tree leaves above ({@code pos.above()}) are the
 * evidence. Even with {@code skipNonNature = false} the primary target must
 * still be leaves — a bare log is never treated as a tree. Natural-tree
 * evidence is conservative and read-only: leaves that are not persistent
 * (naturally grown) or adjacent to a log. The addon's DFS/Brain/BFS
 * validation is never invoked; owner/home constraints and the path check
 * follow the same rules as the built-in farm detector (path is checked
 * against the stand position), and every block/path lookup is budgeted
 * through {@link DetectionContext}.
 */
public final class MaidUsefulTaskTreeDetector implements TaskWorkDetector {
    public static final ResourceLocation UID = new ResourceLocation("maid_useful_task", "maid_tree");
    private static final int TREE_VERTICAL_RANGE = 7;
    private static final int NON_HOME_HORIZONTAL_RANGE = 7;

    private static final Object REFLECT_LOCK = new Object();
    private static boolean reflectResolved;
    private static boolean reflectAvailable;
    private static Method loggingConfigGet;
    private static Method skipNonNatureGetter;

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
        boolean skipNonNature = readSkipNonNature(maid);

        boolean homeMode = maid.isHomeModeEnable();
        BlockPos center = (homeMode ? maid.getRestrictCenter() : maid.blockPosition()).offset(0, 1, 0);
        int horizontalRange = homeMode ? Math.max(0, (int) maid.getRestrictRadius()) : NON_HOME_HORIZONTAL_RANGE;
        TaskScanCursor cursor = context.cursor();
        if (cursor == null || !cursor.matches(center, homeMode, horizontalRange, TREE_VERTICAL_RANGE)) {
            cursor = TaskScanCursor.start(center, homeMode, horizontalRange, TREE_VERTICAL_RANGE,
                    context.currentTick(), List.of());
        }
        int unreachableCandidates = cursor.unreachableCandidates();

        while (cursor != null && context.consumeBlock()) {
            BlockPos standPos = cursor.currentPos();
            TaskScanCursor nextCursor = cursor.advance();

            if (!maid.isWithinRestriction(standPos) || !isNearOwner(maid, standPos)) {
                cursor = nextCursor;
                continue;
            }
            // Stand position must be standable (no collision) and the primary
            // target above it must be leaves — never a bare log, even when
            // skipNonNature is disabled.
            if (!context.level().getBlockState(standPos).getCollisionShape(context.level(), standPos).isEmpty()) {
                cursor = nextCursor;
                continue;
            }
            BlockPos leafPos = standPos.above();
            BlockState leafState = context.level().getBlockState(leafPos);
            if (!leafState.is(BlockTags.LEAVES)) {
                cursor = nextCursor;
                continue;
            }
            if (skipNonNature && !isNaturalTreeLeaf(context.level(), leafPos, leafState)) {
                cursor = nextCursor;
                continue;
            }
            if (!context.consumePathCheck()) {
                context.setCursor(nextCursor);
                return DetectionResult.unknown(task.getUid(), context.currentTick(), "PATH_BUDGET_EXHAUSTED");
            }
            if (maid.canPathReach(standPos)) {
                context.setCursor(null);
                return new DetectionResult(task.getUid(), Availability.AVAILABLE, context.currentTick(),
                        40, 0, skipNonNature ? "NATURAL_TREE_NEARBY" : "TREE_NEARBY", standPos, null);
            }
            unreachableCandidates++;
            cursor = nextCursor == null ? null : nextCursor.withUnreachableCandidates(unreachableCandidates);
        }

        context.setCursor(cursor);
        if (cursor == null) {
            return unavailable(context, task,
                    unreachableCandidates > 0 ? "FULL_SCAN_ALL_UNREACHABLE" : "FULL_SCAN_EMPTY");
        }
        return DetectionResult.unknown(task.getUid(), context.currentTick(), "BLOCK_BUDGET_EXHAUSTED");
    }

    /**
     * Conservative natural-tree signal for a leaves block: either not
     * persistent (naturally grown) or adjacent to a log. The addon's DFS
     * validation is not invoked.
     */
    private static boolean isNaturalTreeLeaf(ServerLevel level, BlockPos leafPos, BlockState leafState) {
        if (leafState.getBlock() instanceof LeavesBlock
                && leafState.hasProperty(LeavesBlock.PERSISTENT)
                && !leafState.getValue(LeavesBlock.PERSISTENT)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(leafPos.relative(direction)).is(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reflection-only read of {@code MaidLoggingConfig.get(maid).skipNonNature()}.
     * Any failure (class missing, signature change, exception) falls back to
     * {@code true} so only natural trees are ever considered.
     */
    private static boolean readSkipNonNature(EntityMaid maid) {
        Method getter = resolveSkipNonNature();
        if (getter == null) {
            return true;
        }
        try {
            Object data = getter.invoke(null, maid);
            if (data == null) {
                return true;
            }
            Method skip = skipNonNatureGetter;
            if (skip == null) {
                skip = data.getClass().getMethod("skipNonNature");
                skipNonNatureGetter = skip;
            }
            return (boolean) skip.invoke(data);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return true;
        }
    }

    private static Method resolveSkipNonNature() {
        synchronized (REFLECT_LOCK) {
            if (reflectResolved) {
                return reflectAvailable ? loggingConfigGet : null;
            }
            reflectResolved = true;
            try {
                Class<?> configClass = Class.forName("studio.fantasyit.maid_useful_task.data.MaidLoggingConfig");
                loggingConfigGet = configClass.getMethod("get", EntityMaid.class);
                reflectAvailable = true;
            } catch (ClassNotFoundException | NoSuchMethodException exception) {
                reflectAvailable = false;
            }
            return reflectAvailable ? loggingConfigGet : null;
        }
    }

    private static boolean isNearOwner(EntityMaid maid, BlockPos pos) {
        if (maid.isHomeModeEnable()) {
            return true;
        }
        LivingEntity owner = maid.getOwner();
        return owner != null && pos.closerToCenterThan(owner.position(), 8);
    }

    private static DetectionResult unavailable(DetectionContext context, IMaidTask task, String evidence) {
        return new DetectionResult(task.getUid(), Availability.UNAVAILABLE, context.currentTick(),
                40, 0, evidence, null, null);
    }
}
