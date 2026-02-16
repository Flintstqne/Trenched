package org.flintstqne.entrenched.RoadLogic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.Optional;
import java.util.UUID;

/**
 * Applies supply-related penalties such as reduced health regeneration.
 * Death/respawn handling is managed by DeathListener.
 */
public final class SupplyPenaltyListener implements Listener {

    private final RoadService roadService;
    private final TeamService teamService;
    private final DeathListener deathListener;

    public SupplyPenaltyListener(RoadService roadService, TeamService teamService, DeathListener deathListener) {
        this.roadService = roadService;
        this.teamService = teamService;
        this.deathListener = deathListener;
    }

    // ==================== HEALTH REGEN PENALTY ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHealthRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();

        // Don't affect players in respawn state
        if (deathListener != null && deathListener.isInRespawnDelay(uuid)) {
            event.setCancelled(true);
            return;
        }

        // Only affect natural regeneration
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED &&
            event.getRegainReason() != EntityRegainHealthEvent.RegainReason.REGEN) {
            return;
        }

        Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        double multiplier = roadService.getHealthRegenMultiplier(uuid, team);

        if (multiplier < 1.0) {
            // Reduce healing amount
            double newAmount = event.getAmount() * multiplier;

            if (newAmount <= 0) {
                event.setCancelled(true);
            } else {
                event.setAmount(newAmount);
            }
        }
    }
}

