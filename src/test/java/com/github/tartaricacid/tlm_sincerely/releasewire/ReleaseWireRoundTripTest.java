package com.github.tartaricacid.tlm_sincerely.releasewire;

import com.github.tartaricacid.tlm_sincerely.releasewire.AutoWorkSnapshot.CompatEntry;
import com.github.tartaricacid.tlm_sincerely.releasewire.AutoWorkSnapshot.MaidEntry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对 v0.2.0-beta（protocol=3）release wire 做 encode→decode round-trip，
 * 验证"同一构建内编码端与解码端 schema 自洽"——联机进服即断连
 * （DecoderException: IndexOutOfBounds）排查用的判定实验。
 *
 * <p>若本测试绿：release wire 自身自洽，两端同构建不会解码越界，
 * 炸因只能是两端 jar 构建不同（同版本号不同内容的 wire 错位）。
 * 若本测试红：release wire 有实打实的 schema 缺陷，须修。
 */
class ReleaseWireRoundTripTest {

    private static MaidEntry maid(String namePrefix, int orderSize) {
        List<ResourceLocation> order = new ArrayList<>();
        for (int i = 0; i < orderSize; i++) {
            order.add(new ResourceLocation("test", "task_" + i));
        }
        return new MaidEntry(UUID.nameUUIDFromBytes(namePrefix.getBytes()), true,
                UUID.nameUUIDFromBytes(("preset-" + namePrefix).getBytes()),
                namePrefix, order, true, 3);
    }

    @Test
    void snapshotRoundTrip_balanced() {
        AutoWorkSnapshot snapshot = new AutoWorkSnapshot(42,
                List.of(new CompatEntry(new ResourceLocation("test", "cook"), "PARTIAL", "PARTIAL_SUPPORT"),
                        new CompatEntry(new ResourceLocation("test", "farm"), "SUPPORTED", "OK")),
                List.of(maid("女仆一号", 5), maid("maid-two", 0), maid("a-very-long-preset-name-十六字节以上宽字符混合", 3)));
        AutoWorkSnapshotS2CPacket msg = new AutoWorkSnapshotS2CPacket(snapshot);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        AutoWorkSnapshotS2CPacket.encode(msg, buf);

        FriendlyByteBuf readBuf = new FriendlyByteBuf(buf.copy());
        AutoWorkSnapshot decoded = AutoWorkSnapshotS2CPacket.decode(readBuf).snapshot();

        assertEquals(snapshot.revision(), decoded.revision());
        assertEquals(snapshot.compatEntries().size(), decoded.compatEntries().size());
        assertEquals(snapshot.maids().size(), decoded.maids().size());
        assertEquals(snapshot.maids().get(0).presetName(), decoded.maids().get(0).presetName());
    }

    @Test
    void snapshotRoundTrip_largePayload() {
        // 逼近联机环境的量级：大量女仆 + 大 order，确认无越界
        List<MaidEntry> maids = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            maids.add(maid("maid-" + i, 40));
        }
        AutoWorkSnapshot snapshot = new AutoWorkSnapshot(1,
                List.of(new CompatEntry(new ResourceLocation("test", "t"), "SUPPORTED", "OK")), maids);
        AutoWorkSnapshotS2CPacket msg = new AutoWorkSnapshotS2CPacket(snapshot);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        AutoWorkSnapshotS2CPacket.encode(msg, buf);
        assertTrue(buf.readableBytes() > 10_000, "应当是大包，实际 " + buf.readableBytes());

        AutoWorkSnapshot decoded = AutoWorkSnapshotS2CPacket.decode(new FriendlyByteBuf(buf.copy())).snapshot();
        assertEquals(maids.size(), decoded.maids().size());
    }

    @Test
    void seedRoundTrip() {
        List<AutoWorkPresetData> presets = List.of(
                new AutoWorkPresetData(UUID.randomUUID(), "默认预设", List.of(new ResourceLocation("test", "a"))),
                new AutoWorkPresetData(UUID.randomUUID(), "seed-two", List.of()));
        AutoWorkSeedS2CPacket msg = new AutoWorkSeedS2CPacket(UUID.randomUUID(), presets);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        AutoWorkSeedS2CPacket.encode(msg, buf);
        AutoWorkSeedS2CPacket decoded = AutoWorkSeedS2CPacket.decode(new FriendlyByteBuf(buf.copy()));

        assertEquals(presets.size(), decoded.presets().size());
        assertEquals(presets.get(0).name(), decoded.presets().get(0).name());
    }

    @Test
    void encode_oversizedName_throwsInsteadOfEmittingGarbage() {
        // 名字 >64 字符：release encode 侧 writeUtf(name,64) 应当直接抛异常
        //（异常在编码端发生 = 半包不会上线，不会表现为对端解码越界）
        AutoWorkPresetData bad = new AutoWorkPresetData(UUID.randomUUID(), "x".repeat(100),
                List.of(new ResourceLocation("test", "a")));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        assertThrows(RuntimeException.class, () -> AutoWorkPresetData.encode(buf, bad));
    }
}
