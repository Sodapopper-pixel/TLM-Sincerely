package com.github.tartaricacid.tlm_sincerely.priority.detection;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record DetectionResult(
        ResourceLocation taskUid,
        Availability availability,
        long sampledTick,
        int ttlTicks,
        int consecutiveConfirmations,
        String evidence,
        BlockPos targetPos,
        UUID targetEntityUuid
) {
    public static DetectionResult unknown(ResourceLocation taskUid, long currentTick, String evidence) {
        return new DetectionResult(taskUid, Availability.UNKNOWN, currentTick, 1, 0, evidence, null, null);
    }

    public boolean isExpired(long currentTick) {
        return currentTick < sampledTick || currentTick - sampledTick > ttlTicks;
    }

    public DetectionResult withConfirmations(int confirmations) {
        return new DetectionResult(taskUid, availability, sampledTick, ttlTicks, confirmations,
                evidence, targetPos, targetEntityUuid);
    }
}
