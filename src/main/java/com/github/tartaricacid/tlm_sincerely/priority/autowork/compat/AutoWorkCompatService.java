package com.github.tartaricacid.tlm_sincerely.priority.autowork.compat;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.PriorityConfig;
import com.github.tartaricacid.tlm_sincerely.priority.detection.TaskWorkDetectorRegistry;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-server compatibility classifier. It answers whether a task has a
 * reliable detector source without probing the world or mutating maid state.
 */
public final class AutoWorkCompatService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkCompatService.class);
    private static final Map<MinecraftServer, AutoWorkCompatService> INSTANCES = new IdentityHashMap<>();
    private static final Set<String> BUILTIN_KNOWN_BAD_FALLBACK = Set.of(
            "touhou_little_maid:feed_animal",
            "maidsoulkitchen:feed_animal_t"
    );
    /**
     * Partial-support tasks: a dedicated detector exists, but it only covers
     * a verified subset. These stay schedulable, yet are always surfaced in
     * the PROBLEMS view so players notice the limitation.
     */
    static final Set<String> BUILTIN_PARTIAL_SUPPORT = Set.of(
            "maidsoulkitchen:cook"
    );
    /** Machine-readable reason kept stable for GUI/Tool snapshots. */
    public static final String PARTIAL_SUPPORT_REASON = "PARTIAL_SUPPORT";
    private static final int REMINDER_DELAY_TICKS = 30;

    /**
     * System fallback task, not a real work target. It is excluded from the
     * compatibility report and its counts, but stays in the registered UID set
     * so configured lists referencing it are still validated.
     */
    public static final ResourceLocation IDLE_UID = new ResourceLocation("touhou_little_maid", "idle");

    private final MinecraftServer server;
    private final Map<ResourceLocation, ReportEntry> report = new LinkedHashMap<>();
    private final Map<UUID, Long> pendingReminderTicks = new LinkedHashMap<>();
    private AutoWorkCompatConfig config;

    private AutoWorkCompatService(MinecraftServer server) {
        this.server = server;
        this.config = AutoWorkCompatIO.loadOrCreate();
        rebuildReport();
    }

    public static AutoWorkCompatService bind(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, AutoWorkCompatService::new);
    }

    public static void unbind(MinecraftServer server) {
        AutoWorkCompatService service = INSTANCES.remove(server);
        if (service != null) {
            service.pendingReminderTicks.clear();
        }
    }

    public static AutoWorkCompatService getOrNull(MinecraftServer server) {
        return server == null ? null : INSTANCES.get(server);
    }

    public void reload() {
        config = AutoWorkCompatIO.loadOrCreate();
        rebuildReport();
    }

    public void rebuildReport() {
        Map<ResourceLocation, ReportEntry> rebuilt = new LinkedHashMap<>();
        Set<String> registeredUids = new LinkedHashSet<>();
        for (IMaidTask task : TaskManager.getTaskIndex()) {
            ResourceLocation uid = task.getUid();
            registeredUids.add(uid.toString());
            if (!IDLE_UID.equals(uid)) {
                rebuilt.put(uid, classify(task));
            }
        }
        report.clear();
        report.putAll(rebuilt);
        warnUnknownConfiguredUids(registeredUids);
        LOGGER.info("[TaskCompat] report rebuilt supported={} fallback={} unsupported={} blocked={}",
                count(Level.SUPPORTED), count(Level.FALLBACK), count(Level.UNSUPPORTED), count(Level.BLOCKED));
    }

    public ReportEntry getEntry(ResourceLocation uid) {
        return report.get(uid);
    }

    public List<ReportEntry> getEntries() {
        return List.copyOf(report.values());
    }

    public List<ReportEntry> problemEntries() {
        List<ReportEntry> problems = new ArrayList<>();
        for (ReportEntry entry : report.values()) {
            if (entry.level() == Level.BLOCKED
                    || (entry.level() == Level.UNSUPPORTED && !config.whitelist().contains(entry.uid().toString()))
                    || BUILTIN_PARTIAL_SUPPORT.contains(entry.uid().toString())) {
                problems.add(entry);
            }
        }
        return problems;
    }

    /** Entries classified at exactly the given level, in registration order. */
    public List<ReportEntry> entriesByLevel(Level level) {
        List<ReportEntry> entries = new ArrayList<>();
        for (ReportEntry entry : report.values()) {
            if (entry.level() == level) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public int count(Level level) {
        int count = 0;
        for (ReportEntry entry : report.values()) {
            if (entry.level() == level) {
                count++;
            }
        }
        return count;
    }

    /** Missing service preserves legacy behavior during server bootstrap only. */
    public boolean isAutoScheduleAllowed(ResourceLocation uid) {
        return isAutoScheduleAllowed(uid, false);
    }

    /**
     * When {@code forcePreselected} is on (experimental), the CONFIG_BLACKLIST
     * classification no longer blocks scheduling; the detector must still
     * report AVAILABLE for the task to be selected. The compat report keeps
     * listing those tasks as BLOCKED.
     */
    public boolean isAutoScheduleAllowed(ResourceLocation uid, boolean forcePreselected) {
        if (IDLE_UID.equals(uid)) {
            return false;
        }
        ReportEntry entry = report.get(uid);
        if (entry == null) {
            return false;
        }
        if (forcePreselected && entry.level() == Level.BLOCKED
                && "CONFIG_BLACKLIST".equals(entry.reason())) {
            return true;
        }
        return entry.autoScheduleAllowed();
    }

    public String summary() {
        return "Task compatibility: supported=%d fallback=%d unsupported=%d blocked=%d".formatted(
                count(Level.SUPPORTED), count(Level.FALLBACK), count(Level.UNSUPPORTED), count(Level.BLOCKED));
    }

    public boolean addBlacklist(ResourceLocation uid) {
        boolean changed = config.addBlacklist(uid.toString());
        if (changed) {
            saveAndRebuild();
        }
        return changed;
    }

    public boolean removeBlacklist(ResourceLocation uid) {
        boolean changed = config.removeBlacklist(uid.toString());
        if (changed) {
            saveAndRebuild();
        }
        return changed;
    }

    public boolean addWhitelist(ResourceLocation uid) {
        boolean changed = config.addWhitelist(uid.toString());
        if (changed) {
            saveAndRebuild();
        }
        return changed;
    }

    public boolean removeWhitelist(ResourceLocation uid) {
        boolean changed = config.removeWhitelist(uid.toString());
        if (changed) {
            saveAndRebuild();
        }
        return changed;
    }

    public Set<String> blacklist() {
        return config.blacklist();
    }

    public Set<String> whitelist() {
        return config.whitelist();
    }

    public ReminderLevel reminderLevel() {
        return config.reminderLevel();
    }

    public void setReminderLevel(ReminderLevel reminderLevel) {
        config.setReminderLevel(reminderLevel);
        AutoWorkCompatIO.save(config);
    }

    public void queueLoginReminder(ServerPlayer player) {
        boolean forceWarning = PriorityConfig.FORCE_ENABLE_PRESELECTED.get();
        if (!forceWarning && !compatReminderEnabled()) {
            return;
        }
        pendingReminderTicks.put(player.getUUID(), (long) server.getTickCount() + REMINDER_DELAY_TICKS);
    }

    public void onServerTick() {
        long currentTick = server.getTickCount();
        List<UUID> due = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : pendingReminderTicks.entrySet()) {
            if (currentTick >= entry.getValue()) {
                due.add(entry.getKey());
            }
        }
        for (UUID playerId : due) {
            pendingReminderTicks.remove(playerId);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && (shouldRemind(player) || PriorityConfig.FORCE_ENABLE_PRESELECTED.get())) {
                sendReminder(player);
            }
        }
    }

    private ReportEntry classify(IMaidTask task) {
        ResourceLocation uid = task.getUid();
        String uidText = uid.toString();
        if (config.blacklist().contains(uidText)) {
            return new ReportEntry(uid, uid.getNamespace(), Level.BLOCKED, "CONFIG_BLACKLIST", false);
        }

        TaskWorkDetectorRegistry.DetectorResolution resolution = TaskWorkDetectorRegistry.resolveWithSource(task);
        if (resolution.source() == TaskWorkDetectorRegistry.DetectorSource.FALLBACK
                && (BUILTIN_KNOWN_BAD_FALLBACK.contains(uidText) || config.knownBadFallback().contains(uidText))) {
            return new ReportEntry(uid, uid.getNamespace(), Level.UNSUPPORTED, "KNOWN_BAD_FALLBACK", false);
        }
        String reason = switch (resolution.source()) {
            case EXACT -> "EXACT_DETECTOR";
            case FALLBACK -> "INTERFACE_FALLBACK";
            case NONE -> config.whitelist().contains(uidText) ? "WHITELIST_WITHOUT_DETECTOR" : "NO_DETECTOR";
        };
        if (BUILTIN_PARTIAL_SUPPORT.contains(uidText) && !"NO_DETECTOR".equals(reason)) {
            reason = PARTIAL_SUPPORT_REASON;
        }
        return switch (resolution.source()) {
            case EXACT -> new ReportEntry(uid, uid.getNamespace(), Level.SUPPORTED, reason, true);
            case FALLBACK -> new ReportEntry(uid, uid.getNamespace(), Level.FALLBACK, reason, true);
            case NONE -> new ReportEntry(uid, uid.getNamespace(), Level.UNSUPPORTED, reason, false);
        };
    }

    private void warnUnknownConfiguredUids(Set<String> registeredUids) {
        Set<String> configured = new LinkedHashSet<>();
        configured.addAll(config.blacklist());
        configured.addAll(config.whitelist());
        configured.addAll(config.knownBadFallback());
        for (String uid : configured) {
            if (!registeredUids.contains(uid)) {
                LOGGER.warn("[TaskCompat] configured UID {} is not registered", uid);
            }
        }
    }

    private void saveAndRebuild() {
        AutoWorkCompatIO.save(config);
        rebuildReport();
    }

    /**
     * Chat compatibility reminders still apply: the per-server reminder level
     * is not DISABLED and the auto-work config switch has not muted them.
     * Does not affect the force-enable-preselected warning.
     */
    private boolean compatReminderEnabled() {
        return !PriorityConfig.DISABLE_COMPAT_REMINDER.get()
                && config.reminderLevel() != ReminderLevel.DISABLED;
    }

    private boolean shouldRemind(ServerPlayer player) {
        if (!compatReminderEnabled()) {
            return false;
        }
        return config.reminderLevel() == ReminderLevel.ALL
                || (config.reminderLevel() == ReminderLevel.OP_ONLY && player.hasPermissions(2));
    }

    private void sendReminder(ServerPlayer player) {
        if (PriorityConfig.FORCE_ENABLE_PRESELECTED.get()) {
            player.sendSystemMessage(Component.translatable(
                    "chat.tlm_sincerely.autowork.force_enable_warning").withStyle(ChatFormatting.GOLD));
        }
        if (!shouldRemind(player) || problemEntries().isEmpty()) {
            return;
        }
        AutoWorkCompatReport.send(player::sendSystemMessage, this, ReportFilter.PROBLEMS, 1);
    }

    public enum Level {
        SUPPORTED,
        FALLBACK,
        UNSUPPORTED,
        BLOCKED
    }

    /**
     * Chat report view filters. PROBLEMS keeps the legacy default view
     * (blocked + non-whitelisted unsupported), ALL lists every registered
     * task, and the remaining values mirror the four compatibility levels.
     */
    public enum ReportFilter {
        PROBLEMS(null),
        ALL(null),
        SUPPORTED(Level.SUPPORTED),
        FALLBACK(Level.FALLBACK),
        UNSUPPORTED(Level.UNSUPPORTED),
        BLOCKED(Level.BLOCKED);

        private final Level level;

        ReportFilter(Level level) {
            this.level = level;
        }

        /** The compatibility level this filter lists, or null for PROBLEMS/ALL. */
        public Level level() {
            return level;
        }

        /** Command words before the page number, e.g. "report all". */
        public String commandPrefix() {
            return switch (this) {
                case PROBLEMS -> "report";
                case ALL -> "report all";
                case SUPPORTED -> "supported";
                case FALLBACK -> "fallback";
                case UNSUPPORTED -> "unsupported";
                case BLOCKED -> "blocked";
            };
        }

        public static ReportFilter forLevel(Level level) {
            return switch (level) {
                case SUPPORTED -> SUPPORTED;
                case FALLBACK -> FALLBACK;
                case UNSUPPORTED -> UNSUPPORTED;
                case BLOCKED -> BLOCKED;
            };
        }
    }

    public enum ReminderLevel {
        ALL,
        OP_ONLY,
        DISABLED
    }

    public record ReportEntry(ResourceLocation uid, String sourceMod, Level level, String reason,
                              boolean autoScheduleAllowed) {
    }
}
