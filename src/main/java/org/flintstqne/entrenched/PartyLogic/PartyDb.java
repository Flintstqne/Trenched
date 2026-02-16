package org.flintstqne.entrenched.PartyLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Database access layer for Parties.
 */
public final class PartyDb implements AutoCloseable {

    private final Connection connection;

    public PartyDb(JavaPlugin plugin) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder");
            }

            File dbFile = new File(dir, "parties.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open parties database", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA foreign_keys = ON");

            // Parties table
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS parties (
                  party_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  round_id INTEGER NOT NULL,
                  team TEXT NOT NULL,
                  leader_uuid TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """);

            // Party members table
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS party_members (
                  player_uuid TEXT NOT NULL,
                  party_id INTEGER NOT NULL,
                  joined_at INTEGER NOT NULL,
                  PRIMARY KEY(player_uuid),
                  FOREIGN KEY(party_id) REFERENCES parties(party_id) ON DELETE CASCADE
                )
                """);

            // Party invites table
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS party_invites (
                  invite_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  inviter_uuid TEXT NOT NULL,
                  invitee_uuid TEXT NOT NULL,
                  party_id INTEGER NOT NULL,
                  invited_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL,
                  FOREIGN KEY(party_id) REFERENCES parties(party_id) ON DELETE CASCADE
                )
                """);
        }
    }

    // ==================== PARTY METHODS ====================

    public int createParty(int roundId, String team, String leaderUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO parties(round_id, team, leader_uuid, created_at) VALUES(?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            ps.setString(3, leaderUuid);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Failed to get party_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create party", e);
        }
    }

    public Optional<Party> getParty(int partyId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM parties WHERE party_id = ?"
        )) {
            ps.setInt(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapParty(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get party", e);
        }
    }

    public void updatePartyLeader(int partyId, String newLeaderUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE parties SET leader_uuid = ? WHERE party_id = ?"
        )) {
            ps.setString(1, newLeaderUuid);
            ps.setInt(2, partyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update party leader", e);
        }
    }

    public void deleteParty(int partyId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM parties WHERE party_id = ?"
        )) {
            ps.setInt(1, partyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete party", e);
        }
    }

    // ==================== MEMBER METHODS ====================

    public void addMember(int partyId, String playerUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO party_members(player_uuid, party_id, joined_at) VALUES(?, ?, ?)"
        )) {
            ps.setString(1, playerUuid);
            ps.setInt(2, partyId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add party member", e);
        }
    }

    public void removeMember(String playerUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM party_members WHERE player_uuid = ?"
        )) {
            ps.setString(1, playerUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove party member", e);
        }
    }

    public Optional<PartyMember> getMembership(String playerUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM party_members WHERE player_uuid = ?"
        )) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapMember(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get party membership", e);
        }
    }

    public List<PartyMember> getMembers(int partyId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM party_members WHERE party_id = ? ORDER BY joined_at ASC"
        )) {
            ps.setInt(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                List<PartyMember> members = new ArrayList<>();
                while (rs.next()) {
                    members.add(mapMember(rs));
                }
                return members;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get party members", e);
        }
    }

    public int countMembers(int partyId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM party_members WHERE party_id = ?"
        )) {
            ps.setInt(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count party members", e);
        }
    }

    // ==================== INVITE METHODS ====================

    public int createInvite(String inviterUuid, String inviteeUuid, int partyId, long expiresAt) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO party_invites(inviter_uuid, invitee_uuid, party_id, invited_at, expires_at) VALUES(?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setString(1, inviterUuid);
            ps.setString(2, inviteeUuid);
            ps.setInt(3, partyId);
            ps.setLong(4, System.currentTimeMillis());
            ps.setLong(5, expiresAt);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Failed to get invite_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create invite", e);
        }
    }

    public Optional<PartyInvite> getInvite(String inviteeUuid, int partyId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM party_invites WHERE invitee_uuid = ? AND party_id = ? AND expires_at > ?"
        )) {
            ps.setString(1, inviteeUuid);
            ps.setInt(2, partyId);
            ps.setLong(3, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapInvite(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get invite", e);
        }
    }

    public List<PartyInvite> getPendingInvites(String inviteeUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM party_invites WHERE invitee_uuid = ? AND expires_at > ? ORDER BY invited_at DESC"
        )) {
            ps.setString(1, inviteeUuid);
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                List<PartyInvite> invites = new ArrayList<>();
                while (rs.next()) {
                    invites.add(mapInvite(rs));
                }
                return invites;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get pending invites", e);
        }
    }

    public void deleteInvite(int inviteId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM party_invites WHERE invite_id = ?"
        )) {
            ps.setInt(1, inviteId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete invite", e);
        }
    }

    public void deleteInvitesForPlayer(String playerUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM party_invites WHERE invitee_uuid = ?"
        )) {
            ps.setString(1, playerUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete invites", e);
        }
    }

    public void deleteExpiredInvites() {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM party_invites WHERE expires_at < ?"
        )) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete expired invites", e);
        }
    }

    // ==================== MAPPING METHODS ====================

    private Party mapParty(ResultSet rs) throws SQLException {
        return new Party(
                rs.getInt("party_id"),
                rs.getInt("round_id"),
                rs.getString("team"),
                rs.getString("leader_uuid"),
                rs.getLong("created_at")
        );
    }

    private PartyMember mapMember(ResultSet rs) throws SQLException {
        return new PartyMember(
                rs.getString("player_uuid"),
                rs.getInt("party_id"),
                rs.getLong("joined_at")
        );
    }

    private PartyInvite mapInvite(ResultSet rs) throws SQLException {
        return new PartyInvite(
                rs.getInt("invite_id"),
                rs.getString("inviter_uuid"),
                rs.getString("invitee_uuid"),
                rs.getInt("party_id"),
                rs.getLong("invited_at"),
                rs.getLong("expires_at")
        );
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}

