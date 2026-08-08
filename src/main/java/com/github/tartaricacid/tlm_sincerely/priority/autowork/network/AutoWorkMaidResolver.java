package com.github.tartaricacid.tlm_sincerely.priority.autowork.network;

import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkPresetService;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkState;
import com.github.tartaricacid.tlm_sincerely.priority.autowork.AutoWorkStateService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Server-side helper that walks loaded maids to repair references to a
 * preset that is about to be deleted (T-2 A3).
 *
 * <p>The fallback is conservative: for every loaded
 * {@link EntityMaid} whose stored {@code presetId} matches the deleted
 * id, the maid's state is rewritten to the default preset. The
 * {@code enabled} flag is preserved so the maid's auto work participation
 * is unchanged. A {@code revision} bump forces the next snapshot to
 * include the new preset id.
 */
public final class AutoWorkMaidResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoWorkMaidResolver.class);

    private AutoWorkMaidResolver() {
    }

    /**
     * @return the number of maids that were rewritten to the default
     *         preset. Returns 0 if the services are not bound or no maids
     *         referenced the preset.
     */
    public static int reassignLoadedMaidsToDefault(MinecraftServer server, UUID deletedPresetId) {
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(server);
        AutoWorkStateService stateService = AutoWorkStateService.getOrNull(server);
        if (presetService == null || stateService == null) {
            return 0;
        }
        UUID newDefault = presetService.getDefaultPresetId();
        if (newDefault.equals(deletedPresetId)) {
            // The service has already advanced its default to another
            // preset; nothing for us to do.
            return 0;
        }
        int rewritten = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (!(entity instanceof EntityMaid maid)) {
                    continue;
                }
                AutoWorkState current = stateService.getState(maid);
                if (!current.presetId().equals(deletedPresetId)) {
                    continue;
                }
                AutoWorkState next = new AutoWorkState(
                        current.enabled(),
                        newDefault,
                        current.revision() + 1
                );
                stateService.setState(maid, next);
                rewritten++;
                LOGGER.info(
                        "[AutoWorkMaidResolver] reassigned maid {} to default preset {} (was {})",
                        maid.getUUID(), newDefault, deletedPresetId
                );
            }
        }
        return rewritten;
    }

    /**
     * Best-effort safety check used by delete handlers: refuses to delete
     * a preset if it is currently the only preset in the library.
     */
    public static boolean isLastPreset(MinecraftServer server, UUID presetId) {
        AutoWorkPresetService presetService = AutoWorkPresetService.getOrNull(server);
        if (presetService == null) {
            return true;
        }
        if (presetService.listPresets().size() <= 1) {
            return true;
        }
        UUID defaultId = presetService.getDefaultPresetId();
        // Refuse to delete the default preset unless another preset exists
        // to take its place. The service's deletePreset() already handles
        // reassignment, but the caller may want to abort earlier.
        return presetId.equals(defaultId) && presetService.listPresets().size() == 1;
    }

    /** Returns the owner uuid of {@code maid}, or null if unowned. */
    @org.jetbrains.annotations.Nullable
    public static UUID ownerUuid(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        LivingEntity owner = maid.getOwner();
        return owner == null ? null : owner.getUUID();
    }
}
