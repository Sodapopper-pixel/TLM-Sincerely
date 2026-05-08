package com.github.tartaricacid.tlm_sincerely.chatbar;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.jetbrains.annotations.Nullable;

public record ChatTarget(@Nullable EntityMaid maid, String message, boolean hasPrefix) {
    public boolean hasMaid() {
        return maid != null;
    }
}