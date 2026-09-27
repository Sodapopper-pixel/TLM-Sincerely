package com.github.tartaricacid.tlm_sincerely.client.autowork;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPreset;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetIO;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The player-private preset library, stored as {@code auto_work_presets.json}
 * under this client's config directory (v3 shape).
 *
 * <p>Only this class writes the file. The server reads it once as a frozen
 * seed; GUI edits, pushed presets and the first-join seed copy all land here
 * (see {@code docs/adr/0004-client-preset-library-and-maid-bound-snapshot.md}).
 * All access is expected from the client main thread.
 */
public final class AutoWorkClientLibrary {
    private static final AutoWorkClientLibrary INSTANCE = new AutoWorkClientLibrary();
    /** Mirrors the wire payload cap so a local preset can always be bound. */
    private static final int MAX_TASKS_PER_PRESET = 512;

    private final Map<UUID, AutoWorkPreset> presets = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private UUID defaultPresetId = AutoWorkPresetIO.LoadedLibrary.makeDefault().getId();
    private boolean loaded;
    /** True while nothing has been read from disk nor edited yet. */
    private boolean pristine = true;

    private AutoWorkClientLibrary() {
    }

    public static AutoWorkClientLibrary get() {
        return INSTANCE;
    }

    public void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (Files.exists(AutoWorkPresetIO.getLibraryFile())) {
            loadFromDisk();
        } else {
            applyLibrary(AutoWorkPresetIO.LoadedLibrary.emptyWithDefault());
            pristine = true;
        }
    }

    /** Drops the in-memory copy; the next access re-reads the file. */
    public void reload() {
        loaded = false;
        pristine = true;
        ensureLoaded();
        notifyListeners();
    }

    public List<AutoWorkPreset> presetsInOrder() {
        ensureLoaded();
        return new ArrayList<>(presets.values());
    }

    public AutoWorkPreset getPreset(UUID id) {
        ensureLoaded();
        return id == null ? null : presets.get(id);
    }

    public UUID defaultPresetId() {
        ensureLoaded();
        return defaultPresetId;
    }

    public AutoWorkPreset defaultPreset() {
        AutoWorkPreset preset = getPreset(defaultPresetId);
        if (preset == null && !presets.isEmpty()) {
            return presets.values().iterator().next();
        }
        return preset;
    }

    public AutoWorkPreset createPreset(String name) {
        ensureLoaded();
        AutoWorkPreset preset = new AutoWorkPreset(UUID.randomUUID(), uniqueName(name), new ArrayList<>());
        presets.put(preset.getId(), preset);
        markEdited();
        return preset;
    }

    public boolean renamePreset(UUID id, String newName) {
        ensureLoaded();
        AutoWorkPreset preset = presets.get(id);
        if (preset == null || newName == null || newName.isEmpty()) {
            return false;
        }
        preset.setName(newName);
        markEdited();
        return true;
    }

    public boolean deletePreset(UUID id) {
        ensureLoaded();
        if (presets.size() <= 1 || !presets.containsKey(id)) {
            return false;
        }
        presets.remove(id);
        if (id.equals(defaultPresetId)) {
            defaultPresetId = presets.keySet().iterator().next();
        }
        markEdited();
        return true;
    }

    public boolean addTask(UUID presetId, ResourceLocation task) {
        ensureLoaded();
        AutoWorkPreset preset = presets.get(presetId);
        if (preset == null || task == null || preset.getOrder().size() >= MAX_TASKS_PER_PRESET) {
            return false;
        }
        if (!preset.addTask(task)) {
            return false;
        }
        markEdited();
        return true;
    }

    public boolean removeTask(UUID presetId, ResourceLocation task) {
        ensureLoaded();
        AutoWorkPreset preset = presets.get(presetId);
        if (preset == null || !preset.removeTask(task)) {
            return false;
        }
        markEdited();
        return true;
    }

    public boolean moveTask(UUID presetId, ResourceLocation task, int targetIndex) {
        ensureLoaded();
        AutoWorkPreset preset = presets.get(presetId);
        if (preset == null) {
            return false;
        }
        int previous = preset.getOrder().indexOf(task);
        if (previous < 0) {
            return false;
        }
        preset.moveTask(task, targetIndex);
        if (preset.getOrder().indexOf(task) == previous) {
            return false;
        }
        markEdited();
        return true;
    }

    /**
     * First-join seed copy: writes the server's frozen seed into this client's
     * library only when the client has no library file and no local edits.
     */
    public void acceptSeed(UUID defaultId, List<AutoWorkPreset> seedPresets) {
        ensureLoaded();
        if (!pristine || Files.exists(AutoWorkPresetIO.getLibraryFile())) {
            return;
        }
        Map<UUID, AutoWorkPreset> map = new LinkedHashMap<>();
        for (AutoWorkPreset preset : seedPresets) {
            map.put(preset.getId(), preset);
        }
        if (map.isEmpty()) {
            return;
        }
        presets.clear();
        presets.putAll(map);
        defaultPresetId = map.containsKey(defaultId) ? defaultId : map.keySet().iterator().next();
        pristine = false;
        persist();
        notifyListeners();
    }

    /**
     * Writes pushed presets into the library, preserving UUIDs. Same-UUID
     * entries are overwritten; display-name collisions with a different UUID
     * are auto-renamed instead of overwriting.
     */
    public PushResult applyPush(List<AutoWorkPreset> incoming) {
        ensureLoaded();
        int created = 0;
        int updated = 0;
        int renamed = 0;
        for (AutoWorkPreset preset : incoming) {
            AutoWorkPreset existing = presets.get(preset.getId());
            if (existing != null) {
                existing.setName(preset.getName());
                existing.getOrder().clear();
                existing.getOrder().addAll(preset.getOrder());
                updated++;
                continue;
            }
            String name = preset.getName();
            if (nameTaken(name, preset.getId())) {
                name = uniqueName(name);
                renamed++;
            }
            presets.put(preset.getId(), new AutoWorkPreset(preset.getId(), name,
                    new ArrayList<>(preset.getOrder())));
            created++;
        }
        markEdited();
        return new PushResult(created, updated, renamed);
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Result of a push apply, used for the receiver's local summary. */
    public record PushResult(int created, int updated, int renamed) {
    }

    private void loadFromDisk() {
        AutoWorkPresetIO.LoadedLibrary library = AutoWorkPresetIO.load();
        applyLibrary(library);
        pristine = false;
    }

    private void applyLibrary(AutoWorkPresetIO.LoadedLibrary library) {
        presets.clear();
        presets.putAll(library.presets());
        defaultPresetId = library.defaultPresetId();
    }

    private void markEdited() {
        pristine = false;
        persist();
        notifyListeners();
    }

    private void persist() {
        AutoWorkPresetIO.save(new AutoWorkPresetIO.LoadedLibrary(
                new LinkedHashMap<>(presets), defaultPresetId));
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // GUI listeners must never break library writes
            }
        }
    }

    private boolean nameTaken(String name, UUID exceptId) {
        String normalized = name.toLowerCase(Locale.ROOT);
        for (AutoWorkPreset preset : presets.values()) {
            if (!preset.getId().equals(exceptId)
                    && preset.getName().toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String uniqueName(String base) {
        String name = base == null || base.isEmpty() ? "预设" : base;
        if (!nameTaken(name, null)) {
            return name;
        }
        Set<String> taken = new HashSet<>();
        for (AutoWorkPreset preset : presets.values()) {
            taken.add(preset.getName().toLowerCase(Locale.ROOT));
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            String candidate = name + " (" + suffix + ")";
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return name + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
