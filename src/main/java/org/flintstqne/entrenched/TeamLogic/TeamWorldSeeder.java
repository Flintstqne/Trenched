package org.flintstqne.entrenched.TeamLogic;

import org.bukkit.Location;
import org.bukkit.World;

import java.awt.*;

public final class TeamWorldSeeder {

    private static final int REGION_SIZE_BLOCKS = 512;
    private static final int HALF_REGION = REGION_SIZE_BLOCKS / 2;
    private static final int SPAWN_Y_OFFSET = 1;

    private TeamWorldSeeder() {}

    public static void seedDefaults(
            TeamService teamService,
            TeamDb teamDb,
            World world,
            Color redDefault,
            Color blueDefault
    ) {
        int redArgb = (redDefault == null) ? 0xFF000033 : redDefault.getRGB();
        int blueArgb = (blueDefault == null) ? 0x0000FF33 : blueDefault.getRGB();

        // FIXED: Third parameter is color, not maxSize
        teamService.createTeam(new Team("red", "Red Team", redArgb));
        teamService.createTeam(new Team("blue", "Blue Team", blueArgb));

        seedTeamCenter(teamService, teamDb, world, "red", -767, -767);
        seedTeamCenter(teamService, teamDb, world, "blue", 767, 767);
    }

    private static Location centerAt(World world, int centerBlockX, int centerBlockZ) {
        int y = world.getHighestBlockYAt(centerBlockX, centerBlockZ) + SPAWN_Y_OFFSET;
        return new Location(world, centerBlockX + 0.5, y, centerBlockZ + 0.5, 0.0f, 0.0f);
    }

    private static void seedTeamCenter(
            TeamService teamService,
            TeamDb teamDb,
            World world,
            String teamId,
            int centerBlockX,
            int centerBlockZ
    ) {
        int cornerBlockX = centerBlockX - HALF_REGION;
        int cornerBlockZ = centerBlockZ - HALF_REGION;

        // Claim the initial region (stored as the region's corner-block key).
        teamDb.claimRegion(cornerBlockX, cornerBlockZ, teamId);

        // Set spawn exactly at the requested center.
        Location spawn = centerAt(world, centerBlockX, centerBlockZ);
        teamService.setTeamSpawn(teamId, spawn);
    }
}
