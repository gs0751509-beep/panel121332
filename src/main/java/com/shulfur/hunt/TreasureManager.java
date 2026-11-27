package com.shulfur.hunt;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Owns all treasure state and persistence. Must only be used from the main server thread. */
public final class TreasureManager {

    public static final int TOTAL = 12;

    private static final Random RANDOM = new Random();

    public record Pos(String world, int x, int y, int z) {
        public static Pos of(Block block) {
            return new Pos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    public static final class Treasure {
        private final int id;
        private final Pos pos;
        private final String clue;
        private boolean collected;
        private String collectedBy;

        private Treasure(int id, Pos pos, String clue, boolean collected, String collectedBy) {
            this.id = id;
            this.pos = pos;
            this.clue = clue;
            this.collected = collected;
            this.collectedBy = collectedBy;
        }

        public int id() { return id; }
        public Pos pos() { return pos; }
        public String clue() { return clue; }
        public boolean isCollected() { return collected; }
        public String collectedBy() { return collectedBy; }

        public Block block() {
            World world = Bukkit.getWorld(pos.world());
            return world == null ? null : world.getBlockAt(pos.x(), pos.y(), pos.z());
        }
    }

    private final HiddenTreasuresPlugin plugin;
    private final File file;
    private final Map<Integer, Treasure> treasures = new TreeMap<>();
    private final Map<Pos, Treasure> byPos = new HashMap<>();
    private boolean running;
    private boolean netherOpen;
    private boolean firstFinderRewarded;

    public TreasureManager(HiddenTreasuresPlugin plugin) {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        running = yml.getBoolean("running", false);
        netherOpen = yml.getBoolean("nether-open", false);
        firstFinderRewarded = yml.getBoolean("first-finder-rewarded", false);
        treasures.clear();
        byPos.clear();

        ConfigurationSection section = yml.getConfigurationSection("treasures");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            int id;
            try {
                id = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                continue;
            }
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null || id < 1 || id > TOTAL) {
                continue;
            }
            Pos pos = new Pos(s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
            Treasure t = new Treasure(id, pos, s.getString("clue", ""),
                    s.getBoolean("collected", false), s.getString("collected-by"));
            treasures.put(id, t);
            byPos.put(pos, t);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("running", running);
        yml.set("nether-open", netherOpen);
        yml.set("first-finder-rewarded", firstFinderRewarded);
        for (Treasure t : treasures.values()) {
            String base = "treasures." + t.id + ".";
            yml.set(base + "world", t.pos.world());
            yml.set(base + "x", t.pos.x());
            yml.set(base + "y", t.pos.y());
            yml.set(base + "z", t.pos.z());
            yml.set(base + "clue", t.clue);
            yml.set(base + "collected", t.collected);
            yml.set(base + "collected-by", t.collectedBy);
        }
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            yml.save(tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public Treasure get(int id) { return treasures.get(id); }

    public Treasure at(Block block) { return byPos.get(Pos.of(block)); }

    /** True when the block is an unclaimed treasure chest. */
    public boolean isProtected(Block block) {
        Treasure t = at(block);
        return t != null && !t.collected;
    }

    public boolean isComplete() { return treasures.size() == TOTAL; }

    public int collectedCount() {
        int count = 0;
        for (Treasure t : treasures.values()) {
            if (t.collected) count++;
        }
        return count;
    }

    public List<Integer> missingIds() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= TOTAL; i++) {
            if (!treasures.containsKey(i)) missing.add(i);
        }
        return missing;
    }

    public List<Integer> uncollectedIds() {
        List<Integer> ids = new ArrayList<>();
        for (Treasure t : treasures.values()) {
            if (!t.collected) ids.add(t.id);
        }
        return ids;
    }

    /** Claimed treasures, ordered by id. */
    public List<Treasure> claimed() {
        List<Treasure> list = new ArrayList<>();
        for (Treasure t : treasures.values()) {
            if (t.collected) list.add(t);
        }
        return list;
    }

    /** Lowest-numbered treasure not yet claimed, or null if none remain. */
    public Treasure nextClue() {
        for (Treasure t : treasures.values()) {
            if (!t.collected) return t;
        }
        return null;
    }

    public boolean isRunning() { return running; }

    public void setRunning(boolean value) {
        running = value;
        save();
    }

    public boolean isNetherOpen() { return netherOpen; }

    public void setNetherOpen(boolean value) {
        netherOpen = value;
        save();
    }

    public boolean isFirstFinderRewarded() { return firstFinderRewarded; }

    public void setFirstFinderRewarded(boolean value) {
        firstFinderRewarded = value;
        save();
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    public void place(int id, Block block, String clue) {
        Treasure old = treasures.remove(id);
        if (old != null) {
            byPos.remove(old.pos);
            clearChest(old.block());
        }
        Treasure t = new Treasure(id, Pos.of(block), clue, false, null);
        treasures.put(id, t);
        byPos.put(t.pos, t);
        fillChest(block);
        save();
    }

    public void remove(int id) {
        Treasure old = treasures.remove(id);
        if (old == null) return;
        byPos.remove(old.pos);
        clearChest(old.block());
        save();
    }

    /** Marks the treasure as claimed by this player name. Chest contents are left for the player to take. */
    public void markClaimed(Treasure t, String playerName) {
        t.collected = true;
        t.collectedBy = playerName;
        save();
    }

    public void reset() {
        for (Treasure t : treasures.values()) {
            t.collected = false;
            t.collectedBy = null;
            fillChest(t.block());
        }
        running = false;
        netherOpen = false;
        firstFinderRewarded = false;
        save();
    }

    // ------------------------------------------------------------------
    // Chest helpers
    // ------------------------------------------------------------------

    private void fillChest(Block block) {
        if (block != null && block.getState() instanceof Chest chest) {
            TreasureItems.fillChest(chest.getBlockInventory(), plugin.getConfig(), RANDOM);
        }
    }

    private static void clearChest(Block block) {
        if (block != null && block.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
        }
    }
}
