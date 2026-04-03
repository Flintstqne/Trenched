package org.flintstqne.entrenched.BlueMapHook;

import de.bluecolored.bluemap.api.BlueMapAPI;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;

import java.util.function.Consumer;
import java.util.logging.Logger;

public class BlueMapIntegration {

    private static Consumer<BlueMapAPI> enableConsumer;
    private static Consumer<BlueMapAPI> disableConsumer;

    /**
     * Call from your plugin's onEnable(). Returns true if BlueMap plugin + API class are reachable.
     * Registers lifecycle callbacks so we react whenever BlueMap finishes loading
     * (even if it loads after our plugin) and handle BlueMap disable/reload.
     *
     * @param plugin      the owning plugin
     * @param onReady     callback invoked every time the BlueMapAPI becomes available
     * @param onShutdown  callback invoked when BlueMapAPI is shutting down (nullable)
     */
    public static boolean initialize(JavaPlugin plugin, Consumer<BlueMapAPI> onReady, Consumer<BlueMapAPI> onShutdown) {
        Logger logger = plugin.getLogger();
        PluginManager pm = plugin.getServer().getPluginManager();
        Plugin bluemap = pm.getPlugin("BlueMap");

        if (bluemap == null) {
            logger.warning("BlueMap plugin not found.");
            return false;
        }

        try {
            Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
        } catch (ClassNotFoundException e) {
            logger.warning("BlueMap API class not found. Ensure BlueMap plugin provides the API on the server.");
            return false;
        } catch (Throwable t) {
            logger.warning("Unexpected error while checking BlueMap API: " + t.getMessage());
            return false;
        }

        // Register lifecycle callbacks — these fire whenever BlueMap finishes
        // initializing (even after a /bluemap reload).
        enableConsumer = api -> {
            logger.info("BlueMap API is now available — registering markers.");
            if (onReady != null) onReady.accept(api);
        };
        BlueMapAPI.onEnable(enableConsumer);

        if (onShutdown != null) {
            disableConsumer = api -> {
                logger.info("BlueMap API shutting down — cleaning up.");
                onShutdown.accept(api);
            };
            BlueMapAPI.onDisable(disableConsumer);
        }

        logger.info("BlueMap API reachable — lifecycle callbacks registered.");
        return true;
    }

    /**
     * Overload that keeps backward compat with callers that don't need callbacks.
     */
    public static boolean initialize(JavaPlugin plugin) {
        return initialize(plugin, null, null);
    }

    /**
     * Call from your plugin's onDisable() to unregister lifecycle callbacks.
     */
    public static void shutdown() {
        if (enableConsumer != null) {
            BlueMapAPI.unregisterListener(enableConsumer);
            enableConsumer = null;
        }
        if (disableConsumer != null) {
            BlueMapAPI.unregisterListener(disableConsumer);
            disableConsumer = null;
        }
    }
}
