package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;
import com.github.tartaricacid.tlm_sincerely.memory.MaidMemory.MemoryEntry;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MemoryMaintenanceManager {
    private static final Logger LOGGER = LogManager.getLogger("TLM_Sincerely/Memory");

    private static final Set<UUID> maintainingMaids = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> pendingTidy = ConcurrentHashMap.newKeySet();

    private static final Map<UUID, Long> tidyStartTime = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> historySnapshotSize = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastToolActivity = new ConcurrentHashMap<>();

    private static final long TIDY_TIMEOUT_MS = 120_000;
    private static final long TOOL_IDLE_THRESHOLD_MS = 30_000;

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
        if (isMaintaining(maidUuid)) {
            lastToolActivity.put(maidUuid, System.currentTimeMillis());
        }
    }

    public static void logMerge(UUID maidUuid, List<String> sourceKeys, String targetKey) {
        LOGGER.info("Maid {} consolidated {} -> {}", maidUuid, sourceKeys, targetKey);
    }

    public static void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 20 != 0) return;

        if (!pendingTidy.isEmpty()) {
            Iterator<UUID> it = pendingTidy.iterator();
            while (it.hasNext()) {
                UUID uuid = it.next();
                it.remove();
                EntityMaid maid = findMaid(server, uuid);
                if (maid != null) {
                    startTidy(maid);
                }
            }
        }

        if (!maintainingMaids.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<UUID> it = maintainingMaids.iterator();
            while (it.hasNext()) {
                UUID uuid = it.next();
                Long startTime = tidyStartTime.get(uuid);
                Long lastActivity = lastToolActivity.get(uuid);

                boolean timeout = startTime != null && (now - startTime) > TIDY_TIMEOUT_MS;
                boolean idle = lastActivity != null && (now - lastActivity) > TOOL_IDLE_THRESHOLD_MS;

                if (timeout || (idle && startTime != null)) {
                    if (timeout) {
                        LOGGER.warn("Maid {} memory maintenance timed out, force finishing", uuid);
                    }
                    finishTidy(server, uuid);
                }
            }
        }
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
        lastToolActivity.put(uuid, now);
        historySnapshotSize.put(uuid, maid.getAiChatManager().getHistory().size());

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
        maid.getAiChatManager().chat(tidyPrompt, clientInfo, player);
    }

    private static void finishTidy(MinecraftServer server, UUID uuid) {
        EntityMaid maid = findMaid(server, uuid);

        if (maid != null) {
            Integer snapshotSize = historySnapshotSize.get(uuid);
            if (snapshotSize != null) {
                var deque = maid.getAiChatManager().getHistory().getDeque();
                int currentSize = deque.size();
                int toRemove = currentSize - snapshotSize;
                for (int i = 0; i < toRemove && !deque.isEmpty(); i++) {
                    deque.pollLast();
                }
                LOGGER.info("Maid {} maintenance finished, removed {} history entries", uuid, toRemove);
            }
        }

        MaidMemory memory = MaidMemoryManager.load(uuid);
        memory.setLastTidyAt(System.currentTimeMillis());
        MaidMemoryManager.save(uuid, memory);

        maintainingMaids.remove(uuid);
        tidyStartTime.remove(uuid);
        historySnapshotSize.remove(uuid);
        lastToolActivity.remove(uuid);
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
