package org.flintstqne.entrenched.BlueMapHook;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import java.util.logging.Logger;

public class BlueMapIntegration {
    /**
     * Call from your plugin's onEnable(). Returns true if BlueMap plugin + API class are reachable.
     */
    public static boolean initialize(JavaPlugin plugin) {
        Logger logger = plugin.getLogger();
        PluginManager pm = plugin.getServer().getPluginManager();
        Plugin bluemap = pm.getPlugin("BlueMap");

        if (bluemap == null || !bluemap.isEnabled()) {
            logger.warning("BlueMap plugin not found or not enabled.");
            return false;
        }

        try {
            // Ensure the API class is on the classpath (adjust class name if your BlueMap version differs)
            Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
            logger.info("BlueMap API reachable.");
            return true;
        } catch (ClassNotFoundException e) {
            logger.warning("BlueMap API class not found. Ensure BlueMap plugin provides the API on the server.");
            return false;
        } catch (Throwable t) {
            logger.warning("Unexpected error while checking BlueMap API: " + t.getMessage());
            return false;
        }
    }
}
