package org.flintstqne.entrenched.LinkLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * SQLite persistence for Discord ↔ Minecraft account links.
 */
public final class LinkDb implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(LinkDb.class.getName());

    private final Connection connection;

    public LinkDb(JavaPlugin plugin) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder");
            }

            File dbFile = new File(dir, "links.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open links database", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS linked_accounts (
                    discord_id  TEXT NOT NULL UNIQUE,
                    mc_uuid     TEXT NOT NULL UNIQUE,
                    mc_username TEXT,
                    linked_at   INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                )
                """);

            st.executeUpdate("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_link_discord ON linked_accounts(discord_id)
                """);
            st.executeUpdate("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_link_mc ON linked_accounts(mc_uuid)
                """);

            // Add mc_username column to databases created before this column existed.
            try {
                st.executeUpdate("ALTER TABLE linked_accounts ADD COLUMN mc_username TEXT");
            } catch (SQLException ignored) {
                // Column already exists — expected for any fresh install using the new schema above.
            }
        }
    }

    /**
     * Insert a new link. Returns false if either ID is already linked (UNIQUE violation).
     */
    public boolean insertLink(String discordId, String mcUuid) {
        String sql = "INSERT OR IGNORE INTO linked_accounts (discord_id, mc_uuid) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, discordId);
            ps.setString(2, mcUuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.severe("Failed to insert link: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove a link by Discord ID. Returns true if a row was deleted.
     */
    public boolean unlinkByDiscord(String discordId) {
        String sql = "DELETE FROM linked_accounts WHERE discord_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, discordId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.severe("Failed to unlink by Discord: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove a link by Minecraft UUID. Returns true if a row was deleted.
     */
    public boolean unlinkByMc(String mcUuid) {
        String sql = "DELETE FROM linked_accounts WHERE mc_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mcUuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.severe("Failed to unlink by MC UUID: " + e.getMessage());
            return false;
        }
    }

    /**
     * Look up the MC UUID linked to a Discord ID.
     */
    public Optional<String> getMcUuid(String discordId) {
        String sql = "SELECT mc_uuid FROM linked_accounts WHERE discord_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, discordId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(rs.getString("mc_uuid"));
            }
            return Optional.empty();
        } catch (SQLException e) {
            LOGGER.severe("Failed to look up MC UUID: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Look up the Discord ID linked to a MC UUID.
     */
    public Optional<String> getDiscordId(String mcUuid) {
        String sql = "SELECT discord_id FROM linked_accounts WHERE mc_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mcUuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(rs.getString("discord_id"));
            }
            return Optional.empty();
        } catch (SQLException e) {
            LOGGER.severe("Failed to look up Discord ID: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Look up the stored Minecraft username for a given MC UUID.
     */
    public Optional<String> getMcUsername(String mcUuid) {
        String sql = "SELECT mc_username FROM linked_accounts WHERE mc_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("mc_username");
                    return name != null ? Optional.of(name) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Failed to get mc_username for " + mcUuid + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Persist the Minecraft username for a linked account (upsert-style UPDATE).
     */
    public boolean updateMcUsername(String mcUuid, String username) {
        String sql = "UPDATE linked_accounts SET mc_username = ? WHERE mc_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, mcUuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Failed to update mc_username for " + mcUuid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if a MC UUID is already linked.
     */
    public boolean isMcLinked(String mcUuid) {
        return getDiscordId(mcUuid).isPresent();
    }

    /**
     * Check if a Discord ID is already linked.
     */
    public boolean isDiscordLinked(String discordId) {
        return getMcUuid(discordId).isPresent();
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOGGER.severe("Failed to close links database: " + e.getMessage());
        }
    }
}

