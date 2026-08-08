package com.github.tartaricacid.tlm_sincerely.memory;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MemoryConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MaidMemory {
    private final Map<String, MemoryEntry> memories;
    private long lastTidyAt;

    public MaidMemory() {
        this.memories = new LinkedHashMap<>();
        this.lastTidyAt = 0;
    }

    public void set(String key, String value, String importance) {
        set(key, value, importance, "");
    }

    public void set(String key, String value, String importance, String source) {
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
            String finalSource = (source != null && !source.isEmpty()) ? source : existing.source();
            memories.put(trimmedKey, new MemoryEntry(
                    trimmedValue, importance,
                    existing.createdAt(), now,
                    existing.lastAccessedAt(), existing.accessCount(),
                    finalSource));
        } else {
            if (memories.size() >= MemoryConfig.MAX_MEMORIES.get()) {
                return;
            }
            memories.put(trimmedKey, new MemoryEntry(
                    trimmedValue, importance,
                    now, now,
                    0, 0,
                    source != null ? source : ""));
        }
    }

    public Optional<MemoryEntry> get(String key) {
        return Optional.ofNullable(memories.get(key));
    }

    public void touch(String key) {
        MemoryEntry existing = memories.get(key);
        if (existing != null) {
            long now = System.currentTimeMillis();
            memories.put(key, new MemoryEntry(
                    existing.value(), existing.importance(),
                    existing.createdAt(), existing.updatedAt(),
                    now, existing.accessCount() + 1,
                    existing.source()));
        }
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

    public Optional<String> findOldestArchiveKey() {
        for (Map.Entry<String, MemoryEntry> entry : memories.entrySet()) {
            if (MemoryEntry.ARCHIVE.equals(entry.getValue().importance())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public List<Map.Entry<String, MemoryEntry>> search(String query) {
        String lowerQuery = query.toLowerCase();
        List<Map.Entry<String, MemoryEntry>> results = new ArrayList<>();
        for (Map.Entry<String, MemoryEntry> entry : memories.entrySet()) {
            if (entry.getKey().toLowerCase().contains(lowerQuery)
                    || entry.getValue().value().toLowerCase().contains(lowerQuery)) {
                results.add(entry);
                if (results.size() >= 10) {
                    break;
                }
            }
        }
        return results;
    }

    public long getLastTidyAt() {
        return lastTidyAt;
    }

    public void setLastTidyAt(long lastTidyAt) {
        this.lastTidyAt = lastTidyAt;
    }

    public String generateContextPreview(int coreLimit, int previewLength) {
        return generateContextPreview(coreLimit, previewLength, "full", false);
    }

    public String generateContextPreview(int coreLimit, int previewLength, String previewMode, boolean showSource) {
        if (memories.isEmpty()) {
            return "None";
        }

        boolean keysOnly = "keys_only".equals(previewMode);

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
                        .append(": ").append(entry.getValue().value());
                if (showSource && !entry.getValue().source().isEmpty()) {
                    sb.append(" (").append(entry.getValue().source()).append(")");
                }
                sb.append("\n");
                coreCount++;
            } else {
                String value = entry.getValue().value();
                String preview = value.length() > previewLength
                        ? value.substring(0, previewLength) + "..."
                        : value;
                sb.append("  ").append(entry.getKey())
                        .append(": \"").append(preview).append("\"");
                if (showSource && !entry.getValue().source().isEmpty()) {
                    sb.append(" (").append(entry.getValue().source()).append(")");
                }
                sb.append("\n");
            }
        }

        for (Map.Entry<String, MemoryEntry> entry : archiveEntries) {
            if (keysOnly) {
                sb.append("  ").append(entry.getKey()).append("\n");
            } else {
                String value = entry.getValue().value();
                String preview = value.length() > previewLength
                        ? value.substring(0, previewLength) + "..."
                        : value;
                sb.append("  ").append(entry.getKey())
                        .append(": \"").append(preview).append("\"");
                if (showSource && !entry.getValue().source().isEmpty()) {
                    sb.append(" (").append(entry.getValue().source()).append(")");
                }
                sb.append("\n");
            }
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

    public record MemoryEntry(
            String value, String importance,
            long createdAt, long updatedAt,
            long lastAccessedAt, int accessCount,
            String source) {
        public static final String CORE = "core";
        public static final String ARCHIVE = "archive";

        public MemoryEntry(String value, String importance, long createdAt, long updatedAt) {
            this(value, importance, createdAt, updatedAt, 0, 0, "");
        }
    }
}
