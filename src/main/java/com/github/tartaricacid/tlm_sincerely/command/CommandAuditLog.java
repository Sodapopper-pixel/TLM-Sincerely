package com.github.tartaricacid.tlm_sincerely.command;

import com.github.tartaricacid.tlm_sincerely.config.subconfig.MaidCommandConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Append-only audit trail for executed commands.
 *
 * <p>Path: {@code <gamedir>/logs/tlm_sincerely/command_audit.log}. Only the
 * meta data of the command is stored (never the full output). The file is
 * rotated to {@code command_audit.log.1} once it exceeds the configured size.
 * Writes only happen on the server thread and never interrupt the command
 * flow: any IO failure is logged as a warning.
 */
public final class CommandAuditLog {
    public enum Decision {
        EXECUTED,
        CONFIRMED_EXECUTED,
        SESSION_TRUSTED_EXECUTED,
        CANCELLED,
        TIMEOUT,
        BLOCKED_BLACKLIST,
        REJECTED_OWNER_OFFLINE,
        RATE_LIMITED,
        INVALID,
        ERROR
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("TLM_Sincerely/CommandAudit");
    private static final String FILE_NAME = "command_audit.log";
    private static final String ROTATED_FILE_NAME = "command_audit.log.1";

    private static Path logFile;

    private CommandAuditLog() {
    }

    public static void log(EntityMaid maid, @Nullable ServerPlayer owner,
                           Decision decision, int resultCode, String command, String hit) {
        String maidName = maid.getName().getString();
        UUID ownerUuid = owner != null ? owner.getUUID() : maid.getOwnerUUID();
        String ownerName = owner != null ? owner.getGameProfile().getName() : "<offline>";
        String line = "%s maid=\"%s\"(%s) owner=\"%s\"(%s) decision=%s result=%s cmd=\"%s\" hit=%s%n"
                .formatted(
                        Instant.now().truncatedTo(ChronoUnit.SECONDS),
                        escape(maidName), maid.getUUID(),
                        escape(ownerName), ownerUuid,
                        decision.name(),
                        resultCode,
                        escape(command),
                        hit == null || hit.isEmpty() ? "-" : escape(hit)
                );
        write(line);
    }

    private static void write(String line) {
        try {
            Path file = resolveFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            rotateIfNeeded(file);
        } catch (Throwable throwable) {
            LOGGER.warn("Failed to write command audit log: {}", throwable.getMessage());
        }
    }

    private static void rotateIfNeeded(Path file) throws IOException {
        long maxBytes = MaidCommandConfig.AUDIT_LOG_MAX_SIZE_MB.get() * 1024L * 1024L;
        if (Files.size(file) <= maxBytes) {
            return;
        }
        Path rotated = file.resolveSibling(ROTATED_FILE_NAME);
        Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path resolveFile() {
        if (logFile == null) {
            logFile = FMLPaths.GAMEDIR.get()
                    .resolve("logs").resolve("tlm_sincerely").resolve(FILE_NAME);
        }
        return logFile;
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }
}
