package com.shulfur.hunt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Places treasure chests at random surface spots around a center point and writes their clues. */
public final class AutoPlacer {

    private static final int MIN_SPACING = 200;
    private static final int MAX_ATTEMPTS = 500;

    private static final String[] FLAVOR = {
            "Where the wind is quiet and the grass runs out, look close.",
            "A lone stone keeps watch over what lies beneath its shadow.",
            "Follow the old path until the trees thin out, then dig no further.",
            "Between the hills, a box waits that no traveller has opened.",
            "Where the water stops and the land rises, something sleeps.",
            "The sun sets on the spot, but only the patient will find it."
    };

    private static final String[] DIRECTIONS = {
            "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"
    };

    private AutoPlacer() {
    }

    /**
     * Places up to TOTAL chests at random distances between minDist and maxDist from center.
     * Returns how many were placed. Treasure IDs are assigned 1..n in placement order.
     */
    public static int place(Location center, int minDist, int maxDist, TreasureManager treasures, Random random) {
        World world = center.getWorld();
        if (world == null) {
            return 0;
        }

        List<Block> placed = new ArrayList<>();
        int attempts = 0;
        while (placed.size() < TreasureManager.TOTAL && attempts < MAX_ATTEMPTS) {
            attempts++;

            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minDist + random.nextDouble() * (maxDist - minDist);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            world.getChunkAt(x >> 4, z >> 4).load(true);
            Block ground = world.getHighestBlockAt(x, z);
            if (ground.isLiquid()) {
                continue;
            }
            if (tooClose(placed, ground)) {
                continue;
            }

            Block chestBlock = ground.getRelative(0, 1, 0);
            chestBlock.setType(Material.CHEST, false);

            int id = placed.size() + 1;
            String clue = buildClue(center, chestBlock.getX(), chestBlock.getZ(), random);
            treasures.place(id, chestBlock, clue);
            placed.add(chestBlock);
        }
        return placed.size();
    }

    private static boolean tooClose(List<Block> placed, Block candidate) {
        long limit = (long) MIN_SPACING * MIN_SPACING;
        for (Block other : placed) {
            long dx = other.getX() - candidate.getX();
            long dz = other.getZ() - candidate.getZ();
            if (dx * dx + dz * dz < limit) {
                return true;
            }
        }
        return false;
    }

    private static String buildClue(Location center, int x, int z, Random random) {
        double dx = x - center.getBlockX();
        double dz = z - center.getBlockZ();

        // Minecraft: north is -Z, east is +X. 0 degrees = north, 90 = east.
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) {
            angle += 360;
        }
        String direction = DIRECTIONS[(int) Math.round(angle / 45.0) % 8];
        long distance = Math.round(Math.sqrt(dx * dx + dz * dz) / 50.0) * 50;

        String flavor = FLAVOR[random.nextInt(FLAVOR.length)];
        return flavor + " Head " + direction + ", about " + distance + " blocks from spawn.";
    }
}