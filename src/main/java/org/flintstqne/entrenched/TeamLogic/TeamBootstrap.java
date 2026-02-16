package org.flintstqne.entrenched.TeamLogic;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.*;

import static org.flintstqne.entrenched.Trenched.WORLD_NAME;

public final class TeamBootstrap {

    private static final Color RED_DEFAULT = new Color(0xFF000033, true);
    private static final Color BLUE_DEFAULT = new Color(0x0000FF33, true);
    static World world = Bukkit.getWorld(WORLD_NAME);


    private TeamBootstrap() {}

    public static TeamDb createDb(JavaPlugin plugin) {
        return new TeamDb(plugin);
    }

    public static TeamService createService(TeamDb db, World world) {
        SqlTeamService service = new SqlTeamService(db);

        // Ensure canonical teams exist with default colors
        TeamWorldSeeder.seedDefaults(service, db, world, RED_DEFAULT, BLUE_DEFAULT);
        return service;
    }
}
