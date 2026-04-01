package org.flintstqne.entrenched.LinkLogic;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages Discord ↔ Minecraft account linking.
 * <p>
 * Flow:
 * 1. Player runs /link in-game → gets a one-time code (1-minute expiry)
 * 2. Player runs /link CODE in Discord
 * 3. Bot calls POST /api/link/verify with code + discord_id
 * 4. This service validates and stores the link
 */
public final class LinkService {

    private static final Logger LOGGER = Logger.getLogger(LinkService.class.getName());

    private static final long CODE_EXPIRY_MS = 60_000; // 1 minute
    private static final long CODE_COOLDOWN_MS = 10_000; // 10 seconds

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private final LinkDb db;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, PendingLink> pendingCodes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public LinkService(LinkDb db) {
        this.db = db;
    }

    public GenerateResult generateCode(UUID playerUuid) {
        if (db.isMcLinked(playerUuid.toString())) {
            return new GenerateResult(null, GenerateStatus.ALREADY_LINKED);
        }

        Long lastGen = cooldowns.get(playerUuid);
        if (lastGen != null && System.currentTimeMillis() - lastGen < CODE_COOLDOWN_MS) {
            return new GenerateResult(null, GenerateStatus.COOLDOWN);
        }

        // Invalidate any previous pending code for this player
        pendingCodes.entrySet().removeIf(e -> e.getValue().playerUuid().equals(playerUuid));

        String code = generateRandomCode();
        while (pendingCodes.containsKey(code)) {
            code = generateRandomCode();
        }

        pendingCodes.put(code, new PendingLink(playerUuid, System.currentTimeMillis()));
        cooldowns.put(playerUuid, System.currentTimeMillis());
        pruneExpired();

        return new GenerateResult(code, GenerateStatus.OK);
    }

    public VerifyResult verify(String code, String discordId) {
        if (code == null || code.isBlank() || discordId == null || discordId.isBlank()) {
            return VerifyResult.INVALID_CODE;
        }

        String normalized = code.toUpperCase().trim();
        // Single-use: remove on first attempt regardless of outcome
        PendingLink pending = pendingCodes.remove(normalized);
        if (pending == null) {
            return VerifyResult.INVALID_CODE;
        }

        if (System.currentTimeMillis() - pending.createdAt() > CODE_EXPIRY_MS) {
            return VerifyResult.EXPIRED;
        }

        if (db.isDiscordLinked(discordId)) {
            return VerifyResult.DISCORD_ALREADY_LINKED;
        }

        if (db.isMcLinked(pending.playerUuid().toString())) {
            return VerifyResult.MC_ALREADY_LINKED;
        }

        boolean ok = db.insertLink(discordId, pending.playerUuid().toString());
        if (!ok) {
            return VerifyResult.DB_ERROR;
        }

        LOGGER.info("[Link] Linked Discord " + discordId + " <-> MC " + pending.playerUuid());
        return VerifyResult.OK;
    }

    /** Look up the MC UUID linked to a Discord ID. */
    public Optional<String> getMcUuid(String discordId) {
        return db.getMcUuid(discordId);
    }

    /** Look up the Discord ID linked to a MC UUID. */
    public Optional<String> getDiscordIdForMc(UUID playerUuid) {
        return db.getDiscordId(playerUuid.toString());
    }

    /** Look up the stored Minecraft username for a MC UUID (may be empty for legacy links). */
    public Optional<String> getMcUsername(UUID playerUuid) {
        return db.getMcUsername(playerUuid.toString());
    }

    /** Persist / update the Minecraft username for a linked account. */
    public boolean updateMcUsername(UUID playerUuid, String username) {
        return db.updateMcUsername(playerUuid.toString(), username);
    }

    public boolean unlinkByDiscord(String discordId) {
        boolean removed = db.unlinkByDiscord(discordId);
        if (removed) LOGGER.info("[Link] Unlinked Discord " + discordId);
        return removed;
    }

    public boolean unlinkByMc(UUID playerUuid) {
        boolean removed = db.unlinkByMc(playerUuid.toString());
        if (removed) LOGGER.info("[Link] Unlinked MC " + playerUuid);
        return removed;
    }

    public boolean isLinked(String discordId) {
        return db.isDiscordLinked(discordId);
    }

    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        pendingCodes.entrySet().removeIf(e -> now - e.getValue().createdAt() > CODE_EXPIRY_MS * 2);
        cooldowns.entrySet().removeIf(e -> now - e.getValue() > CODE_COOLDOWN_MS * 2);
    }

    // ── Inner types ──────────────────────────────────────────────────

    public record PendingLink(UUID playerUuid, long createdAt) {}
    public enum GenerateStatus { OK, ALREADY_LINKED, COOLDOWN }
    public record GenerateResult(String code, GenerateStatus status) {}
    public enum VerifyResult { OK, INVALID_CODE, EXPIRED, DISCORD_ALREADY_LINKED, MC_ALREADY_LINKED, DB_ERROR }
}

