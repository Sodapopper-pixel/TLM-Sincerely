package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Counts command attempts per conversation request chain.
 *
 * <p>TLM reuses the same {@link LLMCallback} instance across tool batches and
 * the following model turns of one request chain, so the callback identity is
 * the chain identity. Weak keys need no manual cleanup once the chain ends.
 */
public final class CommandRateLimiter {
    private static final Map<LLMCallback, Integer> ATTEMPTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CommandRateLimiter() {
    }

    public static int recordAttempt(LLMCallback callback) {
        synchronized (ATTEMPTS) {
            int count = ATTEMPTS.getOrDefault(callback, 0) + 1;
            ATTEMPTS.put(callback, count);
            return count;
        }
    }
}
