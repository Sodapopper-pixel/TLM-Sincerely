package com.github.tartaricacid.tlm_sincerely.priority.autowork.push;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkPresetData;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.AutoWorkPushApplyS2CPacket;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side relay for client-to-client preset pushes.
 *
 * <p>The sender's client command packages its private library selection into a
 * C2S packet; the server keeps one pending offer per target and only delivers
 * the payload after the target accepts in chat. Nothing here touches a maid's
 * bound snapshot.
 *
 * <p>All state is per-server and accessed from the server thread only, mirroring
 * {@code CommandConfirmationService}.
 */
public final class AutoWorkPushService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkPushService.class);
    private static final Map<MinecraftServer, AutoWorkPushService> INSTANCES = new IdentityHashMap<>();
    private static final int TICK_SCAN_INTERVAL = 20;
    private static final int MAX_NAME_DISPLAY = 3;
    /** Matches the 60 s used by the maid command confirmation flow. */
    private static final int PUSH_TIMEOUT_SECONDS = 60;
    private static final int MAX_PENDING_PER_SENDER = 16;
    private static final int MAX_PENDING_TOTAL = 1024;

    private final MinecraftServer server;
    private final Map<UUID, Offer> pending = new LinkedHashMap<>();
    private int tickCounter;

    private AutoWorkPushService(MinecraftServer server) {
        this.server = server;
    }

    public static AutoWorkPushService bind(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, AutoWorkPushService::new);
    }

    public static void unbind(MinecraftServer server) {
        AutoWorkPushService service = INSTANCES.remove(server);
        if (service != null) {
            service.pending.clear();
        }
    }

    public static AutoWorkPushService getOrNull(MinecraftServer server) {
        return server == null ? null : INSTANCES.get(server);
    }

    /** Entry point for the sender's C2S offer packet. Server thread only. */
    public void handleOffer(ServerPlayer sender, boolean broadcast, String targetName,
                            List<AutoWorkPresetData> payload) {
        List<AutoWorkPreset> presets = sanitize(payload);
        if (presets.isEmpty()) {
            sender.sendSystemMessage(Component.translatable(
                    "command.tlm_sincerely.autowork.push.empty").withStyle(ChatFormatting.RED));
            return;
        }
        List<ServerPlayer> targets = new ArrayList<>();
        if (broadcast) {
            if (!sender.hasPermissions(2)) {
                sender.sendSystemMessage(Component.translatable(
                        "command.tlm_sincerely.autowork.push.no_permission").withStyle(ChatFormatting.RED));
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.getUUID().equals(sender.getUUID())) {
                    targets.add(player);
                }
            }
        } else {
            ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
            if (target == null) {
                sender.sendSystemMessage(Component.translatable(
                        "command.tlm_sincerely.autowork.push.no_target", targetName)
                        .withStyle(ChatFormatting.RED));
                return;
            }
            targets.add(target);
        }
        if (targets.isEmpty()) {
            sender.sendSystemMessage(Component.translatable(
                    "command.tlm_sincerely.autowork.push.no_target", targetName)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (pending.size() + targets.size() > MAX_PENDING_TOTAL
                || countPendingFor(sender.getUUID()) + targets.size() > MAX_PENDING_PER_SENDER) {
            sender.sendSystemMessage(Component.translatable(
                    "command.tlm_sincerely.autowork.push.too_many").withStyle(ChatFormatting.RED));
            return;
        }

        int timeoutSeconds = PUSH_TIMEOUT_SECONDS;
        int deadlineTick = server.getTickCount() + timeoutSeconds * 20;
        List<String> names = presetNames(presets);
        for (ServerPlayer target : targets) {
            UUID token = UUID.randomUUID();
            Offer offer = new Offer(token, sender.getUUID(), sender.getName().getString(),
                    target.getUUID(), target.getName().getString(), presets, deadlineTick, timeoutSeconds);
            pending.put(token, offer);
            target.sendSystemMessage(buildOfferMessage(offer, names));
            LOGGER.debug("[AutoWorkPush] offer {} from {} to {}", token,
                    sender.getName().getString(), target.getName().getString());
        }
        sender.sendSystemMessage(Component.translatable(
                "command.tlm_sincerely.autowork.push.waiting",
                String.valueOf(targets.size()),
                Component.translatable("command.tlm_sincerely.autowork.push.names", String.join("、", names)),
                String.valueOf(timeoutSeconds)).withStyle(ChatFormatting.YELLOW));
    }

    /** Handles {@code /tlmautowork preset accept <token>}. */
    public boolean accept(ServerPlayer target, UUID token) {
        Offer offer = pending.get(token);
        if (offer == null || !offer.targetUuid.equals(target.getUUID())) {
            return false;
        }
        pending.remove(token);
        if (server.getTickCount() >= offer.deadlineTick) {
            target.sendSystemMessage(Component.translatable(
                    "command.tlm_sincerely.autowork.push.unknown_offer").withStyle(ChatFormatting.GRAY));
            notifySender(offer, "command.tlm_sincerely.autowork.push.timeout", ChatFormatting.GRAY);
            return true;
        }
        List<AutoWorkPresetData> payload = new ArrayList<>(offer.presets.size());
        for (AutoWorkPreset preset : offer.presets) {
            payload.add(AutoWorkPresetData.from(preset));
        }
        AutoWorkNetworking.channel().send(
                PacketDistributor.PLAYER.with(() -> target),
                new AutoWorkPushApplyS2CPacket(payload));
        notifySender(offer, "command.tlm_sincerely.autowork.push.success", ChatFormatting.GREEN);
        LOGGER.info("[AutoWorkPush] {} accepted push from {} ({} presets)",
                target.getName().getString(), offer.senderName, offer.presets.size());
        return true;
    }

    /** Handles {@code /tlmautowork preset reject <token>}. */
    public boolean reject(ServerPlayer target, UUID token) {
        Offer offer = pending.get(token);
        if (offer == null || !offer.targetUuid.equals(target.getUUID())) {
            return false;
        }
        pending.remove(token);
        notifySender(offer, "command.tlm_sincerely.autowork.push.rejected", ChatFormatting.RED);
        LOGGER.info("[AutoWorkPush] {} rejected push from {}",
                target.getName().getString(), offer.senderName);
        return true;
    }

    public void onServerTick() {
        if (++tickCounter % TICK_SCAN_INTERVAL != 0 || pending.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        List<Offer> expired = new ArrayList<>();
        for (Offer offer : pending.values()) {
            if (now >= offer.deadlineTick) {
                expired.add(offer);
            }
        }
        for (Offer offer : expired) {
            pending.remove(offer.token);
            notifySender(offer, "command.tlm_sincerely.autowork.push.timeout", ChatFormatting.GRAY);
        }
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        List<Offer> owned = new ArrayList<>();
        for (Offer offer : pending.values()) {
            if (offer.targetUuid.equals(player.getUUID())) {
                owned.add(offer);
            }
        }
        for (Offer offer : owned) {
            pending.remove(offer.token);
            notifySender(offer, "command.tlm_sincerely.autowork.push.timeout", ChatFormatting.GRAY);
        }
    }

    private int countPendingFor(UUID senderUuid) {
        int count = 0;
        for (Offer offer : pending.values()) {
            if (offer.senderUuid.equals(senderUuid)) {
                count++;
            }
        }
        return count;
    }

    private void notifySender(Offer offer, String key, ChatFormatting color) {
        ServerPlayer sender = server.getPlayerList().getPlayer(offer.senderUuid);
        if (sender == null) {
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(offer.targetUuid);
        String targetName = target != null ? target.getName().getString() : offer.targetName;
        sender.sendSystemMessage(Component.translatable(key, targetName).withStyle(color));
    }

    private MutableComponent buildOfferMessage(Offer offer, List<String> names) {
        MutableComponent message = Component.translatable(
                "command.tlm_sincerely.autowork.push.offer",
                offer.senderName,
                String.valueOf(offer.presets.size()),
                String.join("、", names),
                String.valueOf(offer.timeoutSeconds)
        ).withStyle(ChatFormatting.YELLOW);
        message.append(Component.literal(" "));
        message.append(button("accept", offer.token, ChatFormatting.GREEN));
        message.append(Component.literal(" "));
        message.append(button("reject", offer.token, ChatFormatting.RED));
        return message;
    }

    private MutableComponent button(String key, UUID token, ChatFormatting color) {
        return Component.translatable("command.tlm_sincerely.autowork.push.button." + key)
                .withStyle(Style.EMPTY
                        .withColor(color)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tlmautowork preset " + key + " " + token)));
    }

    private static List<String> presetNames(List<AutoWorkPreset> presets) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < presets.size() && i < MAX_NAME_DISPLAY; i++) {
            names.add(presets.get(i).getName());
        }
        if (presets.size() > MAX_NAME_DISPLAY) {
            names.add("…");
        }
        return names;
    }

    /** Drops malformed payload entries and task UIDs this server does not know. */
    private static List<AutoWorkPreset> sanitize(List<AutoWorkPresetData> payload) {
        List<AutoWorkPreset> presets = new ArrayList<>();
        if (payload != null) {
            for (AutoWorkPresetData data : payload) {
                if (data == null || data.id() == null) {
                    continue;
                }
                String name = data.name() == null ? "" : data.name().trim();
                name = name.replace('\u00a7', ' ').trim();
                if (name.isEmpty()) {
                    continue;
                }
                List<ResourceLocation> order = new ArrayList<>();
                for (ResourceLocation task : AutoWorkStateService.sanitizeOrder(data.order())) {
                    if (TaskManager.findTask(task).isPresent()) {
                        order.add(task);
                    }
                }
                presets.add(new AutoWorkPreset(data.id(), name, order));
                if (presets.size() >= AutoWorkPresetData.MAX_PRESETS) {
                    break;
                }
            }
        }
        return presets;
    }

    private static final class Offer {
        final UUID token;
        final UUID senderUuid;
        final String senderName;
        final UUID targetUuid;
        final String targetName;
        final List<AutoWorkPreset> presets;
        final int deadlineTick;
        final int timeoutSeconds;

        Offer(UUID token, UUID senderUuid, String senderName, UUID targetUuid, String targetName,
              List<AutoWorkPreset> presets, int deadlineTick, int timeoutSeconds) {
            this.token = token;
            this.senderUuid = senderUuid;
            this.senderName = senderName;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.presets = List.copyOf(presets);
            this.deadlineTick = deadlineTick;
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
