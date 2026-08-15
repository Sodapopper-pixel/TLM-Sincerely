package com.github.tartaricacid.tlm_sincerely.priority.autowork.compat;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Mutable server-side configuration loaded from auto_work_compat.json. */
final class AutoWorkCompatConfig {
    private final LinkedHashSet<String> blacklist;
    private final LinkedHashSet<String> whitelist;
    private final LinkedHashSet<String> knownBadFallback;
    private AutoWorkCompatService.ReminderLevel reminderLevel;

    AutoWorkCompatConfig(Set<String> blacklist, Set<String> whitelist, Set<String> knownBadFallback,
                         AutoWorkCompatService.ReminderLevel reminderLevel) {
        this.blacklist = normalizedCopy(blacklist);
        this.whitelist = normalizedCopy(whitelist);
        this.knownBadFallback = normalizedCopy(knownBadFallback);
        this.reminderLevel = reminderLevel == null
                ? AutoWorkCompatService.ReminderLevel.OP_ONLY : reminderLevel;
    }

    static AutoWorkCompatConfig defaults() {
        return new AutoWorkCompatConfig(Set.of(), Set.of(), Set.of(),
                AutoWorkCompatService.ReminderLevel.OP_ONLY);
    }

    Set<String> blacklist() {
        return Collections.unmodifiableSet(blacklist);
    }

    Set<String> whitelist() {
        return Collections.unmodifiableSet(whitelist);
    }

    Set<String> knownBadFallback() {
        return Collections.unmodifiableSet(knownBadFallback);
    }

    AutoWorkCompatService.ReminderLevel reminderLevel() {
        return reminderLevel;
    }

    boolean addBlacklist(String uid) {
        return blacklist.add(normalize(uid));
    }

    boolean removeBlacklist(String uid) {
        return blacklist.remove(normalize(uid));
    }

    boolean addWhitelist(String uid) {
        return whitelist.add(normalize(uid));
    }

    boolean removeWhitelist(String uid) {
        return whitelist.remove(normalize(uid));
    }

    void setReminderLevel(AutoWorkCompatService.ReminderLevel reminderLevel) {
        this.reminderLevel = reminderLevel;
    }

    private static LinkedHashSet<String> normalizedCopy(Set<String> values) {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                copy.add(normalized);
            }
        }
        return copy;
    }

    private static String normalize(String uid) {
        return uid == null ? "" : uid.trim();
    }
}
