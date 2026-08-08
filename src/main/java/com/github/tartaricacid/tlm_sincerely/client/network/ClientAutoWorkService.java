package com.github.tartaricacid.tlm_sincerely.client.network;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkSnapshot;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.network.packets.RequestAutoWorkSnapshotC2SPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Client-side cache for the latest auto work switch snapshot (T-2 A3).
 *
 * <p>Holds the most recent {@link AutoWorkSnapshot} the server sent for
 * the currently connected server. GUI proxies (added in a later stage)
 * should call {@link #current()} from their render code; if it is
 * {@code null} they should call {@link #requestRefresh()} and render a
 * placeholder.
 *
 * <p>No data is ever written to disk. The cache is intentionally
 * thread-safe via {@link CopyOnWriteArrayList} for the listener list;
 * the snapshot field is read-mostly and assigned atomically.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientAutoWorkService {
    private static final ClientAutoWorkService INSTANCE = new ClientAutoWorkService();

    private volatile AutoWorkSnapshot snapshot;
    private final List<Consumer<AutoWorkSnapshot>> listeners = new CopyOnWriteArrayList<>();

    private ClientAutoWorkService() {
    }

    public static ClientAutoWorkService get() {
        return INSTANCE;
    }

    /** Stores the latest snapshot and notifies listeners. */
    public void accept(AutoWorkSnapshot snapshot) {
        this.snapshot = snapshot;
        for (Consumer<AutoWorkSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // listeners are GUI-side; never propagate here.
            }
        }
    }

    /** Returns the cached snapshot, or empty if none has been received yet. */
    public Optional<AutoWorkSnapshot> current() {
        AutoWorkSnapshot s = this.snapshot;
        if (s == null) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    /** Convenience: returns the cached snapshot, or {@code null} if absent. */
    public AutoWorkSnapshot snapshotOrNull() {
        return this.snapshot;
    }

    /**
     * Requests a fresh snapshot from the server. Safe to call from the
     * client main thread; the actual network send happens immediately
     * because {@link RequestAutoWorkSnapshotC2SPacket} is a small packet.
     */
    public void requestRefresh() {
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        com.github.tartaricacid.tlm_sincerely.priority.autowork.network.AutoWorkNetworking
                .channel().sendToServer(new RequestAutoWorkSnapshotC2SPacket());
    }

    /** Registers a listener to be invoked whenever a new snapshot arrives. */
    public void addListener(Consumer<AutoWorkSnapshot> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<AutoWorkSnapshot> listener) {
        listeners.remove(listener);
    }

    /** Drops the cached snapshot. Useful on disconnect. */
    public void clear() {
        this.snapshot = null;
        listeners.clear();
    }

    /** Returns the cached entry for a maid, or empty if not visible. */
    public Optional<AutoWorkSnapshot.MaidEntry> findMaid(UUID maidId) {
        AutoWorkSnapshot s = this.snapshot;
        if (s == null) {
            return Optional.empty();
        }
        for (AutoWorkSnapshot.MaidEntry entry : s.maids()) {
            if (entry.maidId().equals(maidId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Returns the cached entry for a preset, or empty if not present. */
    public Optional<AutoWorkSnapshot.PresetEntry> findPreset(UUID presetId) {
        AutoWorkSnapshot s = this.snapshot;
        if (s == null) {
            return Optional.empty();
        }
        for (AutoWorkSnapshot.PresetEntry entry : s.presets()) {
            if (entry.id().equals(presetId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Read-only view of the preset library. */
    public List<AutoWorkSnapshot.PresetEntry> presets() {
        AutoWorkSnapshot s = this.snapshot;
        if (s == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(s.presets());
    }
}
