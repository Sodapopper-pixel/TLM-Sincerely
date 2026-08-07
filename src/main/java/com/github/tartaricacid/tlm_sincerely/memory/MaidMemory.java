package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MaidMemory {
    private final Map<String, MemoryEntry> memories;

    public MaidMemory() {
        this.memories = new LinkedHashMap<>();
    }

    public void set(String key, String value, String importance) {
        String trimmedKey = key.trim();
        String trimmedValue = value.trim();

        if (trimmedKey.isEmpty() || trimmedValue.isEmpty()) {
            return;
        }
        if (trimmedKey.length() > 64) {
            trimmedKey = trimmedKey.substring(0, 64);
        }
        if (trimmedValue.length() > 500) {
            trimmedValue = trimmedValue.substring(0, 500);
        }
        if (!MemoryEntry.CORE.equals(importance) && !MemoryEntry.ARCHIVE.equals(importance)) {
            importance = MemoryEntry.ARCHIVE;
        }

        long now = System.currentTimeMillis();
        MemoryEntry existing = memories.get(trimmedKey);
        if (existing != null) {
            memories.put(trimmedKey, new MemoryEntry(trimmedValue, importance, existing.createdAt, now));
        } else {
            if (memories.size() >= MemoryConfig.MAX_MEMORIES.get()) {
                return;
            }
            memories.put(trimmedKey, new MemoryEntry(trimmedValue, importance, now, now));
        }
    }

    public Optional<MemoryEntry> get(String key) {
        return Optional.ofNullable(memories.get(key));
    }

    public void forget(String key) {
        memories.remove(key);
    }

    public List<String> keys() {
        return new ArrayList<>(memories.keySet());
    }

    public int size() {
        return memories.size();
    }

    public boolean isEmpty() {
        return memories.isEmpty();
    }

    public Map<String, MemoryEntry> getMemories() {
        return memories;
    }

    public String generateContextPreview(int coreLimit, int previewLength) {
        if (memories.isEmpty()) {
            return "None";
        }

        List<Map.Entry<String, MemoryEntry>> coreEntries = new ArrayList<>();
        List<Map.Entry<String, MemoryEntry>> archiveEntries = new ArrayList<>();

        for (Map.Entry<String, MemoryEntry> entry : memories.entrySet()) {
            if (MemoryEntry.CORE.equals(entry.getValue().importance())) {
                coreEntries.add(entry);
            } else {
                archiveEntries.add(entry);
            }
        }

        StringBuilder sb = new StringBuilder();

        int coreCount = 0;
        for (Map.Entry<String, MemoryEntry> entry : coreEntries) {
            if (coreCount < coreLimit) {
                sb.append("  ").append(entry.getKey())
                        .append(": ").append(entry.getValue().value())
                        .append("\n");
                coreCount++;
            } else {
                String value = entry.getValue().value();
                String preview = value.length() > previewLength
                        ? value.substring(0, previewLength) + "..."
                        : value;
                sb.append("  ").append(entry.getKey())
                        .append(": \"").append(preview).append("\"")
                        .append("\n");
            }
        }

        for (Map.Entry<String, MemoryEntry> entry : archiveEntries) {
            String value = entry.getValue().value();
            String preview = value.length() > previewLength
                    ? value.substring(0, previewLength) + "..."
                    : value;
            sb.append("  ").append(entry.getKey())
                    .append(": \"").append(preview).append("\"")
                    .append("\n");
        }

        int coreTotal = coreEntries.size();
        int archiveTotal = archiveEntries.size();
        if (coreTotal > 0 || archiveTotal > 0) {
            sb.append(coreTotal + archiveTotal).append(" memories total");
            if (coreTotal > 0) {
                sb.append(", ").append(coreTotal).append(" core");
            }
            if (archiveTotal > 0) {
                sb.append(", ").append(archiveTotal).append(" archive");
            }
        }

        return sb.toString();
    }

    public record MemoryEntry(String value, String importance, long createdAt, long updatedAt) {
        public static final String CORE = "core";
        public static final String ARCHIVE = "archive";
    }
}
