package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks TLM chat callback identity across asynchronous LLM/tool continuations.
 *
 * <p><b>维护链判定（标记机制）</b>：维护 prompt 经由 TLM 的 {@code MaidAIChatManager.chat}
 * 发出，存在两条到达 {@link #register} 的路径，判定机制按路径配对：
 * <ul>
 *   <li><b>同步路径</b>：{@code chat()} 直接执行 {@code tryToChat}，LLMCallback 在
 *       startTidy 的调用栈上构造，此时以 {@code INTERNAL_MAINTENANCE_CALL} ThreadLocal 判定；</li>
 *   <li><b>延迟路径</b>：{@code tryCompressBeforeChat} 命中时 TLM 不执行本次对话，把
 *       {@code tryToChat} 推迟到历史摘要回调完成后经 {@code runOnServerThread} 在主线程补发，
 *       此时 ThreadLocal 已被 finally 清除，改为消费 startTidy 预置的按女仆 UUID 维护标记
 *       （check-and-consume，见 MemoryMaintenanceManager）。标记只被精确的
 *       {@code LLMCallback} 实例消费——HistorySummaryCallback 等子类不消费，避免摘要链
 *       被误判为维护链（其 complete 会提前结束维护）。</li>
 * </ul>
 * 两条路径共用同一标记生命周期：判为维护链、维护结束或标记过期时标记即失效，
 * 不会泄漏到后续普通聊天。
 *
 * <p><b>记账配对</b>：一次会话链 = 一次 LLMCallback 构造（register：chain 创建时配平
 * PENDING_ORDINARY 并累加对应 in-flight 计数）+ 一次最终回调（onSuccess/onFailure 的
 * complete：配平 in-flight 计数并收尾维护链）。若 TLM 的 {@code shouldStopChat} 在
 * HTTP 回调入口静默丢弃结果，complete 永不触发，滞留条目由 {@link #sweepStaleEntries()}
 * 兜底清理，清理逻辑与 complete 的失败路径等价且幂等。
 */
public final class MemoryChatTracker {
    private static final Logger LOGGER = LogManager.getLogger("TLM_Sincerely/Memory");

    private static final Map<LLMCallback, ChatRequest> REQUESTS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<List<LLMMessage>, ChatChain> CHAINS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<UUID, AtomicInteger> PENDING_ORDINARY = new ConcurrentHashMap<>();
    /** 每个未消费 PENDING_ORDINARY 的最近入队时间，用于兜底清扫滞留条目。 */
    private static final Map<UUID, Long> PENDING_ORDINARY_SINCE = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> ORDINARY_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> MAINTENANCE_IN_FLIGHT = new ConcurrentHashMap<>();

    /**
     * 维护链滞留阈值，与 MemoryMaintenanceManager.TIDY_TIMEOUT_MS 对齐：
     * 超时收尾释放维护状态后，残留的维护链条目按同一节奏被清扫。
     */
    private static final long MAINTENANCE_STALE_MS = 120_000;
    /**
     * 普通链滞留阈值，放宽到 10 分钟：多轮工具调用的合法长对话可能持续
     * 16 轮 × 60s（TLM 单轮 HTTP 超时），阈值过短会误杀仍在飞行的活跃链。
     */
    private static final long ORDINARY_STALE_MS = 600_000;
    /** PENDING_ORDINARY 滞留阈值：延迟路径正常等待补发不超过一次摘要请求（HTTP 超时 60s）。 */
    private static final long PENDING_ORDINARY_STALE_MS = 120_000;

    private MemoryChatTracker() {
    }

    public static void beginOrdinaryChat(UUID maidUuid) {
        PENDING_ORDINARY.computeIfAbsent(maidUuid, ignored -> new AtomicInteger()).incrementAndGet();
        PENDING_ORDINARY_SINCE.put(maidUuid, System.currentTimeMillis());
    }

    public static void finishSynchronousSubmission(UUID maidUuid, boolean deferredByHistorySummary) {
        if (!deferredByHistorySummary) {
            decrementPendingOrdinary(maidUuid);
        }
    }

    public static void register(LLMCallback callback, EntityMaid maid, List<LLMMessage> messages) {
        ChatChain chain;
        synchronized (CHAINS) {
            chain = CHAINS.get(messages);
            if (chain == null) {
                boolean historySummary = callback.getClass().getName().endsWith(".HistorySummaryCallback");
                // HistorySummaryCallback 永不判为维护链：维护 prompt 的 chat() 同步栈上
                // 也可能触发历史压缩并构造它，若判为维护链，其 complete 会提前结束维护。
                boolean maintenance = false;
                if (!historySummary) {
                    if (MemoryMaintenanceManager.isInternalMaintenanceCall()) {
                        // 同步路径：startTidy 调用栈上的 ThreadLocal 判定
                        maintenance = true;
                    } else if (callback.getClass() == LLMCallback.class) {
                        // 延迟路径：ThreadLocal 已清除，消费 startTidy 预置的维护标记
                        maintenance = MemoryMaintenanceManager.consumePendingMaintenanceRegistration(maid.getUUID());
                    }
                }
                if (maintenance) {
                    // 从任一路径判为维护链后预置标记即失效，避免被后续普通聊天误消费
                    MemoryMaintenanceManager.clearPendingMaintenanceRegistration(maid.getUUID());
                    MAINTENANCE_IN_FLIGHT.computeIfAbsent(maid.getUUID(), ignored -> new AtomicInteger()).incrementAndGet();
                } else {
                    if (!historySummary) {
                        decrementPendingOrdinary(maid.getUUID());
                    }
                    ORDINARY_IN_FLIGHT.computeIfAbsent(maid.getUUID(), ignored -> new AtomicInteger()).incrementAndGet();
                }
                chain = new ChatChain(maid.getUUID(), maintenance, historySummary, System.currentTimeMillis());
                CHAINS.put(messages, chain);
            }
        }
        REQUESTS.put(callback, new ChatRequest(chain, messages, maid, System.currentTimeMillis()));
        if (chain.maintenance()) {
            MemoryMaintenanceManager.recordToolActivity(maid.getUUID());
        }
    }

    public static boolean isMaintenance(LLMCallback callback) {
        ChatRequest request = REQUESTS.get(callback);
        return request != null && request.chain().maintenance();
    }

    public static boolean hasOrdinaryInFlight(UUID maidUuid) {
        return count(PENDING_ORDINARY, maidUuid) > 0 || count(ORDINARY_IN_FLIGHT, maidUuid) > 0;
    }

    public static void reconcilePendingSubmission(UUID maidUuid, boolean historySummaryRunning) {
        if (!historySummaryRunning) {
            PENDING_ORDINARY.remove(maidUuid);
            PENDING_ORDINARY_SINCE.remove(maidUuid);
        }
    }

    public static boolean hasMaintenanceInFlight(UUID maidUuid) {
        return count(MAINTENANCE_IN_FLIGHT, maidUuid) > 0;
    }

    public static void complete(LLMCallback callback, EntityMaid maid) {
        ChatRequest request;
        synchronized (REQUESTS) {
            request = REQUESTS.remove(callback);
        }
        if (request == null) {
            return;
        }
        // maid 与 request.maid() 是同一实例，统一从 request 取以保证幂等路径一致
        finishRequest(request);
    }

    /**
     * 兜底清扫滞留的记账条目，由 onServerTick 周期调用（服务器主线程）。
     *
     * <p>TLM 的 {@code LLMOpenAIClient.handle} 在 HTTP 回调入口用 {@code shouldStopChat}
     * （女仆为空或已死亡）直接 return，onSuccess/onFailure 均不触发，complete() 永不执行；
     * 压缩延迟路径的摘要请求被静默丢弃时 PENDING_ORDINARY 也没有任何 register 会消费它。
     * 清理逻辑与 complete 的失败路径等价（结束链、配平计数、释放维护状态），且因
     * complete 先移除 REQUESTS 条目而天然幂等，不会与正常完成路径重复清理。
     */
    static void sweepStaleEntries() {
        long now = System.currentTimeMillis();
        sweepStalePendingOrdinary(now);
        // 先处理有 request 的条目（连带结束 chain），再兜底移除无 request 对应的孤儿 chain
        sweepStaleRequests(now);
        sweepStaleOrphanChains(now);
    }

    private static void sweepStalePendingOrdinary(long now) {
        PENDING_ORDINARY_SINCE.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < PENDING_ORDINARY_STALE_MS) {
                return false;
            }
            PENDING_ORDINARY.remove(entry.getKey());
            LOGGER.warn("Maid {} had a pending ordinary chat stuck for over {} ms, cleared it",
                    entry.getKey(), PENDING_ORDINARY_STALE_MS);
            return true;
        });
    }

    private static void sweepStaleRequests(long now) {
        List<ChatRequest> stale = new ArrayList<>();
        synchronized (REQUESTS) {
            Iterator<ChatRequest> it = REQUESTS.values().iterator();
            while (it.hasNext()) {
                ChatRequest request = it.next();
                if (now - request.createdAt() >= staleThreshold(request.chain())) {
                    it.remove();
                    stale.add(request);
                }
            }
        }
        for (ChatRequest request : stale) {
            LOGGER.warn("Chat callback for maid {} never completed within {} ms, releasing its chain",
                    request.chain().maidUuid(), staleThreshold(request.chain()));
            finishRequest(request);
        }
    }

    private static void sweepStaleOrphanChains(long now) {
        // releaseMaintenanceChain 只移除 CHAINS 条目、留下同链 REQUESTS 交给 complete/sweep
        // 消化；这里兜底移除无 request 对应的过期 chain。计数不在此处配平：维护链的
        // in-flight 计数已随 releaseMaintenanceChain 清理，普通链不存在孤儿 chain 路径。
        synchronized (CHAINS) {
            CHAINS.entrySet().removeIf(entry ->
                    now - entry.getValue().createdAt() >= staleThreshold(entry.getValue()));
        }
    }

    private static long staleThreshold(ChatChain chain) {
        return chain.maintenance() ? MAINTENANCE_STALE_MS : ORDINARY_STALE_MS;
    }

    /**
     * 结束 request 及其 chain 的全部记账。request 必须已从 REQUESTS 移除；
     * CHAINS 已被移除时直接返回，保证重复调用安全。
     */
    private static void finishRequest(ChatRequest request) {
        ChatChain chain = request.chain();
        synchronized (CHAINS) {
            if (CHAINS.remove(request.messages()) == null) {
                return;
            }
        }
        synchronized (REQUESTS) {
            REQUESTS.entrySet().removeIf(entry -> entry.getValue().chain() == chain);
        }
        if (chain.maintenance()) {
            decrement(MAINTENANCE_IN_FLIGHT, chain.maidUuid());
            MemoryMaintenanceManager.onMaintenanceCallbackCompleted(request.maid());
        } else {
            decrement(ORDINARY_IN_FLIGHT, chain.maidUuid());
        }
    }

    public static void releaseMaintenanceChain(UUID maidUuid) {
        synchronized (CHAINS) {
            CHAINS.entrySet().removeIf(entry -> entry.getValue().maidUuid().equals(maidUuid)
                    && entry.getValue().maintenance());
        }
        MAINTENANCE_IN_FLIGHT.remove(maidUuid);
    }

    public static void clear() {
        REQUESTS.clear();
        CHAINS.clear();
        PENDING_ORDINARY.clear();
        PENDING_ORDINARY_SINCE.clear();
        ORDINARY_IN_FLIGHT.clear();
        MAINTENANCE_IN_FLIGHT.clear();
    }

    private static int count(Map<UUID, AtomicInteger> counts, UUID maidUuid) {
        AtomicInteger count = counts.get(maidUuid);
        return count == null ? 0 : count.get();
    }

    /** PENDING_ORDINARY 专用配平：计数归零时同步移除入队时间戳，保证清扫判定不失真。 */
    private static void decrementPendingOrdinary(UUID maidUuid) {
        AtomicInteger count = PENDING_ORDINARY.get(maidUuid);
        if (count == null) {
            return;
        }
        if (count.decrementAndGet() <= 0) {
            PENDING_ORDINARY.remove(maidUuid, count);
            PENDING_ORDINARY_SINCE.remove(maidUuid);
        }
    }

    private static void decrement(Map<UUID, AtomicInteger> counts, UUID maidUuid) {
        AtomicInteger count = counts.get(maidUuid);
        if (count != null && count.decrementAndGet() <= 0) {
            counts.remove(maidUuid, count);
        }
    }

    private record ChatRequest(ChatChain chain, List<LLMMessage> messages, EntityMaid maid, long createdAt) {
    }

    private record ChatChain(UUID maidUuid, boolean maintenance, boolean historySummary, long createdAt) {
    }
}
