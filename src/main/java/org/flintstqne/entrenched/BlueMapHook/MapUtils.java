package org.flintstqne.entrenched.BlueMapHook;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public final class MapUtils {

    // 32 chunks * 16 blocks
    public static final int REGION_BLOCKS = 512;
    public static final int HALF_REGION_BLOCKS = REGION_BLOCKS / 2;

    public static final int RED_SPAWN_X = -767;
    public static final int RED_SPAWN_Z = -767;
    public static final int BLUE_SPAWN_X = 767;
    public static final int BLUE_SPAWN_Z = 767;

    private MapUtils() {}

    public enum TeamId {
        RED,
        BLUE
    }

    public record RegionKey(int cornerX, int cornerZ) {}

    public record RegionInfo(
            RegionKey key,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            int centerX,
            int centerZ,
            String id,
            String name
    ) {}

    public static Location hardcodedTeamSpawn(World world, TeamId teamId) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(teamId, "teamId");

        int x = (teamId == TeamId.RED) ? RED_SPAWN_X : BLUE_SPAWN_X;
        int z = (teamId == TeamId.RED) ? RED_SPAWN_Z : BLUE_SPAWN_Z;

        int y = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, y, z + 0.5, 0.0f, 0.0f);
    }

    public static RegionKey regionKeyForBlock(int blockX, int blockZ) {
        int cornerX = floorToRegionCorner(blockX);
        int cornerZ = floorToRegionCorner(blockZ);
        return new RegionKey(cornerX, cornerZ);
    }

    public static RegionInfo regionInfoForBlock(int blockX, int blockZ) {
        RegionKey key = regionKeyForBlock(blockX, blockZ);
        return regionInfoForKey(key);
    }

    public static RegionInfo regionInfoForKey(RegionKey key) {
        Objects.requireNonNull(key, "key");

        int x0 = key.cornerX();
        int z0 = key.cornerZ();
        int x1 = x0 + REGION_BLOCKS;
        int z1 = z0 + REGION_BLOCKS;

        int centerX = x0 + HALF_REGION_BLOCKS;
        int centerZ = z0 + HALF_REGION_BLOCKS;

        int gridX = Math.floorDiv(x0, REGION_BLOCKS);
        int gridZ = Math.floorDiv(z0, REGION_BLOCKS);

        String id = gridX + "," + gridZ;
        String name = "Region " + id;

        return new RegionInfo(
                key,
                x0,
                z0,
                x1,
                z1,
                centerX,
                centerZ,
                id,
                name
        );
    }

    public static boolean regionContainsBlock(RegionKey key, int blockX, int blockZ) {
        Objects.requireNonNull(key, "key");
        int x0 = key.cornerX();
        int z0 = key.cornerZ();

        return blockX >= x0 && blockX < x0 + REGION_BLOCKS
                && blockZ >= z0 && blockZ < z0 + REGION_BLOCKS;
    }

    private static int floorToRegionCorner(int blockCoord) {
        return Math.floorDiv(blockCoord, REGION_BLOCKS) * REGION_BLOCKS;
    }
}
