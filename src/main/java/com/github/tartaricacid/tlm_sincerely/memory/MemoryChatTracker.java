package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Tracks TLM chat callback identity across asynchronous LLM/tool continuations. */
public final class MemoryChatTracker {
    private static final Map<LLMCallback, ChatRequest> REQUESTS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<List<LLMMessage>, ChatChain> CHAINS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<UUID, AtomicInteger> PENDING_ORDINARY = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> ORDINARY_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> MAINTENANCE_IN_FLIGHT = new ConcurrentHashMap<>();

    private MemoryChatTracker() {
    }

    public static void beginOrdinaryChat(UUID maidUuid) {
        PENDING_ORDINARY.computeIfAbsent(maidUuid, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public static void finishSynchronousSubmission(UUID maidUuid, boolean deferredByHistorySummary) {
        if (!deferredByHistorySummary) {
            decrement(PENDING_ORDINARY, maidUuid);
        }
    }

    public static void register(LLMCallback callback, EntityMaid maid, List<LLMMessage> messages) {
        ChatChain chain;
        synchronized (CHAINS) {
            chain = CHAINS.get(messages);
            if (chain == null) {
                boolean maintenance = MemoryMaintenanceManager.isInternalMaintenanceCall();
                boolean historySummary = callback.getClass().getName().endsWith(".HistorySummaryCallback");
                chain = new ChatChain(maid.getUUID(), maintenance, historySummary);
                CHAINS.put(messages, chain);
                if (!maintenance) {
                    if (!historySummary) {
                        decrement(PENDING_ORDINARY, maid.getUUID());
                    }
                    ORDINARY_IN_FLIGHT.computeIfAbsent(maid.getUUID(), ignored -> new AtomicInteger()).incrementAndGet();
                } else {
                    MAINTENANCE_IN_FLIGHT.computeIfAbsent(maid.getUUID(), ignored -> new AtomicInteger()).incrementAndGet();
                }
            }
        }
        REQUESTS.put(callback, new ChatRequest(chain, messages));
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
            MemoryMaintenanceManager.onMaintenanceCallbackCompleted(maid);
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
        ORDINARY_IN_FLIGHT.clear();
        MAINTENANCE_IN_FLIGHT.clear();
    }

    private static int count(Map<UUID, AtomicInteger> counts, UUID maidUuid) {
        AtomicInteger count = counts.get(maidUuid);
        return count == null ? 0 : count.get();
    }

    private static void decrement(Map<UUID, AtomicInteger> counts, UUID maidUuid) {
        AtomicInteger count = counts.get(maidUuid);
        if (count != null && count.decrementAndGet() <= 0) {
            counts.remove(maidUuid, count);
        }
    }

    private record ChatRequest(ChatChain chain, List<LLMMessage> messages) {
    }

    private record ChatChain(UUID maidUuid, boolean maintenance, boolean historySummary) {
    }
}
