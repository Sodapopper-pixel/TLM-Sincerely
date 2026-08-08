package com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S: request a fresh snapshot. No payload. */
public final class RequestAutoWorkSnapshotC2SPacket {
    public static final int INDEX = 1;

    public static void encode(RequestAutoWorkSnapshotC2SPacket msg, FriendlyByteBuf buf) {
        // No payload; the request alone triggers a snapshot.
    }

    public static RequestAutoWorkSnapshotC2SPacket decode(FriendlyByteBuf buf) {
        return new RequestAutoWorkSnapshotC2SPacket();
    }

    public static void handle(RequestAutoWorkSnapshotC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            NetworkEvent.Context c = ctx.get();
            AutoWorkServerHandler.sendSnapshot(c.getSender());
        });
        ctx.get().setPacketHandled(true);
    }
}
