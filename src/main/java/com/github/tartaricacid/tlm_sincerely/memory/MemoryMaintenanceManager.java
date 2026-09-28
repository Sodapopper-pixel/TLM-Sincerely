package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 记忆维护调度器。维护链的判定采用"双机制配对"设计（详见 MemoryChatTracker 类注释）：
 * <ul>
 *   <li>同步路径：startTidy 的 chat() 调用栈上以 {@link #INTERNAL_MAINTENANCE_CALL}
 *       ThreadLocal 判定；</li>
 *   <li>延迟路径：TLM 的 tryCompressBeforeChat 命中时把 tryToChat 推迟到历史摘要回调
 *       完成后补发，ThreadLocal 已失效，此时以 {@link #PENDING_MAINTENANCE_REGISTRATION}
 *       按女仆 UUID 预置的标记（check-and-consume）判定。</li>
 * </ul>
 */
public final class MemoryMaintenanceManager {
    private static final Logger LOGGER = LogManager.getLogger("TLM_Sincerely/Memory");

    private static final Set<UUID> maintainingMaids = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> pendingTidy = ConcurrentHashMap.newKeySet();

    private static final Map<UUID, Long> tidyStartTime = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<LLMMessage>> historySnapshot = new ConcurrentHashMap<>();

    /**
     * 按女仆 UUID 预置的维护标记：startTidy 发起 chat() 前置位，由该女仆维护链的首个
     * LLMCallback 在 register 时以 check-and-consume 方式消费（延迟路径专用）。
     */
    private static final Map<UUID, Long> PENDING_MAINTENANCE_REGISTRATION = new ConcurrentHashMap<>();

    static final ThreadLocal<Boolean> INTERNAL_MAINTENANCE_CALL = ThreadLocal.withInitial(() -> false);

    private static final long TIDY_TIMEOUT_MS = 120_000;
    /** 维护标记有效期：覆盖延迟路径中最长的一次历史摘要请求（TLM 单轮 HTTP 超时为 60s）。 */
    private static final long PENDING_REGISTRATION_TTL_MS = TIDY_TIMEOUT_MS;

    private static int tickCounter = 0;

    private static final String TIDY_PROMPT = """
            You are performing memory maintenance. Below is the full list of archived memories.
            %s
            Identify groups of entries that describe the SAME fact or event, not merely related topics.
            For each group, call tlm_memory action=merge with the source keys and one merged value that preserves every concrete detail (who/what/when/where).
            Do not merge entries that are only topically related. Prefer fewer, well-merged entries.
            If nothing needs merging, reply exactly "No maintenance needed." and make no tool calls.""";

    private MemoryMaintenanceManager() {
    }

    public static boolean isInternalMaintenanceCall() {
        return INTERNAL_MAINTENANCE_CALL.get();
    }

    /**
     * startTidy 发起 chat() 前预置维护标记（check-and-consume 的置位端）。
     * 压缩延迟路径下 tryToChat 会在历史摘要回调完成后于主线程补发，届时
     * INTERNAL_MAINTENANCE_CALL 已清除，MemoryChatTracker.register 依赖该标记判定维护链。
     */
    static void markPendingMaintenanceRegistration(UUID maidUuid) {
        PENDING_MAINTENANCE_REGISTRATION.put(maidUuid, System.currentTimeMillis());
    }

    /**
     * register 时消费维护标记（check-and-consume 的消费端）。过期标记视为不存在，
     * 防止滞留标记把后续普通聊天误判为维护链。
     */
    static boolean consumePendingMaintenanceRegistration(UUID maidUuid) {
        Long markedAt = PENDING_MAINTENANCE_REGISTRATION.remove(maidUuid);
        return markedAt != null && System.currentTimeMillis() - markedAt <= PENDING_REGISTRATION_TTL_MS;
    }

    /** 无论维护链从哪条路径完成注册，预置标记都应立即失效，避免被后续普通聊天误消费。 */
    static void clearPendingMaintenanceRegistration(UUID maidUuid) {
        PENDING_MAINTENANCE_REGISTRATION.remove(maidUuid);
    }

    public static boolean isMaintaining(UUID maidUuid) {
        return maintainingMaids.contains(maidUuid);
    }

    public static void checkAndScheduleTidy(EntityMaid maid) {
        if (!MemoryConfig.TIDY_ENABLED.get()) return;
        if (isMaintaining(maid.getUUID())) return;
        if (pendingTidy.contains(maid.getUUID())) return;

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());

        int max = MemoryConfig.MAX_MEMORIES.get();
        int threshold = (int) Math.ceil(max * MemoryConfig.TIDY_THRESHOLD.get());
        if (memory.size() < threshold) return;

        long cooldownMs = MemoryConfig.TIDY_COOLDOWN_MINUTES.get() * 60_000L;
        if (System.currentTimeMillis() - memory.getLastTidyAt() < cooldownMs) return;

        pendingTidy.add(maid.getUUID());
        LOGGER.info("Maid {} scheduled for memory maintenance (size={}, threshold={})",
                maid.getUUID(), memory.size(), threshold);
    }

    public static void recordToolActivity(UUID maidUuid) {
        // Callback identity, not idle timing, is authoritative for completion.
    }

    public static void logMerge(UUID maidUuid, List<String> sourceKeys, String targetKey) {
        LOGGER.info("Maid {} consolidated {} -> {}", maidUuid, sourceKeys, targetKey);
    }

    public static void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 20 != 0) return;

        // 兜底清扫 TLM 回调链路滞留的记账条目（回调被 TLM shouldStopChat 静默吞掉时
        // onSuccess/onFailure 均不触发，complete 永不执行），见 MemoryChatTracker#sweepStaleEntries
        MemoryChatTracker.sweepStaleEntries();
        sweepStaleMaintenanceRegistrations();

        if (!pendingTidy.isEmpty()) {
            Iterator<UUID> it = pendingTidy.iterator();
            while (it.hasNext()) {
                UUID uuid = it.next();
                it.remove();
                EntityMaid maid = findMaid(server, uuid);
                if (maid != null) {
                    MemoryChatTracker.reconcilePendingSubmission(uuid,
                            maid.getAiChatManager().historySummaryRunning);
                    if (MemoryChatTracker.hasOrdinaryInFlight(uuid)) {
                        pendingTidy.add(uuid);
                    } else {
                        startTidy(maid);
                    }
                }
            }
        }

        if (!maintainingMaids.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<UUID> it = maintainingMaids.iterator();
            while (it.hasNext()) {
                UUID uuid = it.next();
                Long startTime = tidyStartTime.get(uuid);
                boolean timeout = startTime != null && (now - startTime) > TIDY_TIMEOUT_MS;
                if (timeout) {
                    LOGGER.warn("Maid {} memory maintenance timed out, terminating this maintenance generation", uuid);
                    finishTidy(findMaid(server, uuid), uuid);
                    MemoryChatTracker.releaseMaintenanceChain(uuid);
                }
            }
        }
    }

    /** 过期仍未消费的维护标记直接清除，防止滞留标记把后续普通聊天误判为维护链。 */
    private static void sweepStaleMaintenanceRegistrations() {
        long now = System.currentTimeMillis();
        PENDING_MAINTENANCE_REGISTRATION.entrySet().removeIf(entry ->
                now - entry.getValue() > PENDING_REGISTRATION_TTL_MS);
    }

    private static void startTidy(EntityMaid maid) {
        LivingEntity owner = maid.getOwner();
        if (!(owner instanceof ServerPlayer player)) {
            LOGGER.info("Maid {} skipped maintenance: owner not online", maid.getUUID());
            MaidMemory mem = MaidMemoryManager.load(maid.getUUID());
            mem.setLastTidyAt(System.currentTimeMillis());
            MaidMemoryManager.save(maid.getUUID(), mem);
            return;
        }

        LLMSite site = maid.getAiChatManager().getLLMSite();
        if (site == null) {
            LOGGER.info("Maid {} skipped maintenance: LLM site unavailable", maid.getUUID());
            MaidMemory memory = MaidMemoryManager.load(maid.getUUID());
            memory.setLastTidyAt(System.currentTimeMillis());
            MaidMemoryManager.save(maid.getUUID(), memory);
            return;
        }

        UUID uuid = maid.getUUID();
        maintainingMaids.add(uuid);
        long now = System.currentTimeMillis();
        tidyStartTime.put(uuid, now);
        Set<LLMMessage> originalHistory = Collections.newSetFromMap(new IdentityHashMap<>());
        originalHistory.addAll(maid.getAiChatManager().getHistory().getDeque());
        historySnapshot.put(uuid, originalHistory);

        MaidMemory memory = MaidMemoryManager.load(maid.getUUID());
        StringBuilder archiveList = new StringBuilder();
        for (Map.Entry<String, MemoryEntry> entry : memory.getMemories().entrySet()) {
            if (MemoryEntry.ARCHIVE.equals(entry.getValue().importance())) {
                archiveList.append("  ").append(entry.getKey())
                        .append(": ").append(entry.getValue().value()).append("\n");
            }
        }

        String tidyPrompt = TIDY_PROMPT.formatted(archiveList.toString());

        String language = maid.getAiChatManager().getTTSLanguage();
        if (language == null || language.isEmpty()) {
            language = "en_us";
        }
        ChatClientInfo clientInfo = new ChatClientInfo(language, maid.getName().getString(), List.of());

        LOGGER.info("Maid {} starting memory maintenance", uuid);
        // 预置维护标记：chat() 若命中 TLM 的 tryCompressBeforeChat，本次维护对话会被
        // 推迟到历史摘要回调完成后补发，届时 ThreadLocal 已清除，register 靠该标记判定
        markPendingMaintenanceRegistration(uuid);
        INTERNAL_MAINTENANCE_CALL.set(true);
        try {
            maid.getAiChatManager().chat(tidyPrompt, clientInfo, player);
        } finally {
            INTERNAL_MAINTENANCE_CALL.remove();
        }
    }

    /**
     * 结束一次维护并清理全部维护状态。maid 可能为 null（超时路径下女仆已卸载/死亡），
     * 此时仅跳过历史清理；调用方必须保证本方法在服务器主线程执行。
     */
    private static void finishTidy(@Nullable EntityMaid maid, UUID uuid) {
        if (maid != null) {
            Set<LLMMessage> originalHistory = historySnapshot.get(uuid);
            if (originalHistory != null) {
                LinkedBlockingDeque<LLMMessage> deque = maid.getAiChatManager().getHistory().getDeque();
                int before = deque.size();
                deque.removeIf(message -> !originalHistory.contains(message));
                LOGGER.info("Maid {} maintenance finished, removed {} history entries",
                        uuid, before - deque.size());
            }
        }

        MaidMemory memory = MaidMemoryManager.load(uuid);
        memory.setLastTidyAt(System.currentTimeMillis());
        MaidMemoryManager.save(uuid, memory);

        maintainingMaids.remove(uuid);
        tidyStartTime.remove(uuid);
        historySnapshot.remove(uuid);
        PENDING_MAINTENANCE_REGISTRATION.remove(uuid);
    }

    /**
     * Completes maintenance after the full asynchronous LLM/tool callback chain ends.
     *
     * <p>complete() 可能由 LLM 的 HTTP 回调线程触发（TLM 的 HttpClient.sendAsync 直接在
     * HTTP 线程回调 onSuccess/onFailure），而 finishTidy 需要访问女仆的历史队列等实体
     * 状态，因此这里把收尾调度回服务器主线程；已在主线程（如 sweep 兜底路径）时直接执行。
     * 上下文直接携带 maid 实例，不再跨线程重查实体索引。
     */
    public static void onMaintenanceCallbackCompleted(EntityMaid maid) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return;
        }
        UUID uuid = maid.getUUID();
        if (!historySnapshot.containsKey(uuid)) {
            return;
        }
        MinecraftServer server = level.getServer();
        Runnable finish = () -> {
            // 超时路径可能已先行清理，二次检查保证幂等
            if (!historySnapshot.containsKey(uuid)) {
                return;
            }
            finishTidy(maid, uuid);
            MemoryChatTracker.releaseMaintenanceChain(uuid);
        };
        if (server.isSameThread()) {
            finish.run();
        } else {
            server.submit(finish);
        }
    }

    public static void clearRuntimeState() {
        maintainingMaids.clear();
        pendingTidy.clear();
        tidyStartTime.clear();
        historySnapshot.clear();
        PENDING_MAINTENANCE_REGISTRATION.clear();
        MemoryChatTracker.clear();
        tickCounter = 0;
        LOGGER.info("MemoryMaintenanceManager cleared runtime state");
    }

    private static EntityMaid findMaid(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof EntityMaid maid) {
                return maid;
            }
        }
        return null;
    }
}
