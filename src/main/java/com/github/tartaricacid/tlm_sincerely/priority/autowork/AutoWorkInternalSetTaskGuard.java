package com.github.tartaricacid.tlm_sincerely.priority.autowork;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Thread-local marker used by the auto work switch to identify calls to
 * {@code EntityMaid.setTask} that originate from the decision engine.
 *
 * <p>The auto work switcher is the <b>only</b> component allowed to call
 * {@code maid.setTask()} for automatic scheduling. Future external-task
 * compatibility layers (e.g. mixins or other addons that intercept task
 * changes) can use {@link #isInternalSetTask()} to recognise our own
 * writes and avoid treating them as a competing source of changes.
 *
 * <p>Usage: wrap the {@code setTask} call with
 * {@link #runInternal(UUID, ResourceLocation, Runnable)} so the flag is
 * always cleared even if the call throws.
 */
public final class AutoWorkInternalSetTaskGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkInternalSetTaskGuard.class);

    /**
     * Per-thread current internal call context. A non-null value means
     * "we are inside a {@code setTask} triggered by the decision engine
     * right now". The maid UUID and the target task UID are exposed so
     * future compatibility layers can correlate.
     */
    private static final ThreadLocal<CallContext> CURRENT = new ThreadLocal<>();

    private AutoWorkInternalSetTaskGuard() {
    }

    /** Returns true if the current thread is executing an internal setTask. */
    public static boolean isInternalSetTask() {
        return CURRENT.get() != null;
    }

    /** Returns the maid UUID of the in-progress internal setTask, or null. */
    public static UUID currentMaidUuid() {
        CallContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.maidUuid();
    }

    /** Returns the target task UID of the in-progress internal setTask, or null. */
    public static ResourceLocation currentTargetTask() {
        CallContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.targetTask();
    }

    /**
     * Runs {@code action} with the internal-setTask guard set. The guard
     * is cleared in a {@code finally} block so exceptions cannot leak the
     * flag to subsequent code on the same thread.
     */
    public static void runInternal(UUID maidUuid, ResourceLocation targetTask, Runnable action) {
        CURRENT.set(new CallContext(maidUuid, targetTask));
        try {
            action.run();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * Lightweight one-shot probe intended for future external-compat
     * layers. Invokes {@code predicate} while the guard is set; returns
     * the result and always clears the guard afterwards.
     */
    public static <T> T withInternal(UUID maidUuid, ResourceLocation targetTask,
                                     java.util.function.Supplier<T> action) {
        CURRENT.set(new CallContext(maidUuid, targetTask));
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * Defensive: if for any reason the guard is still set at the end of
     * a server tick, clear it. This avoids stale flags bleeding into the
     * next call site on the same thread. Safe to call from anywhere on
     * the server thread; logs a warning when it has to clear a leak.
     */
    public static void clearIfStale(String source) {
        if (CURRENT.get() != null) {
            LOGGER.warn("[AutoWorkGuard] clearing stale internal-setTask flag from {}", source);
            CURRENT.remove();
        }
    }

    private record CallContext(UUID maidUuid, ResourceLocation targetTask) {
    }
}
