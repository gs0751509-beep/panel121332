package com.shulfur.hunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builds the items placed in each treasure chest and rolls random loot. */
public final class TreasureItems {

    /** Middle slot of the chest. The Shulfur Core always sits here. */
    public static final int CORE_SLOT = 13;

    /** Every other slot, spread out so the items are not bunched together. */
    private static final int[] SPREAD_SLOTS = {
            0, 26, 4, 22, 8, 18, 2, 24, 6, 20, 10, 16,
            1, 25, 3, 23, 5, 21, 7, 19, 9, 17, 11, 15
    };

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static NamespacedKey coreKey;

    private TreasureItems() {
    }

    /** Must be called once in onEnable, before any Core is created or checked. */
    public static void init(JavaPlugin plugin) {
        coreKey = new NamespacedKey(plugin, "shulfur_core");
    }

    /** The glowing Shulfur Core: the Obsidian Block that marks each treasure. */
    public static ItemStack createCore(FileConfiguration cfg) {
        ItemStack item = new ItemStack(Material.OBSIDIAN, 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize(cfg.getString("reward.name", "<gold><b>Shulfur Core</b></gold>"))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList("reward.lore")) {
            lore.add(MM.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(coreKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** True only for a Shulfur Core, not for ordinary obsidian. */
    public static boolean isCore(ItemStack item) {
        if (item == null || item.getType() != Material.OBSIDIAN || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(coreKey, PersistentDataType.BYTE);
    }

    /** Fills a chest: Core in the middle, everything else spread around it. */
    public static void fillChest(Inventory inv, FileConfiguration cfg, Random random) {
        inv.clear();
        inv.setItem(CORE_SLOT, createCore(cfg));

        int slot = 0;
        for (ItemStack item : rollRewards(cfg, random)) {
            if (slot < SPREAD_SLOTS.length) {
                inv.setItem(SPREAD_SLOTS[slot++], item);
            } else {
                inv.addItem(item);
            }
        }
    }

    /** Everything that goes in a chest besides the Core. */
    public static List<ItemStack> rollRewards(FileConfiguration cfg, Random random) {
        List<ItemStack> items = new ArrayList<>();
        items.add(createNetheriteUpgrade());
        ItemStack trim = rollTrim(cfg, random);
        if (trim != null) {
            items.add(trim);
        }
        items.addAll(rollLoot(cfg, random));
        return items;
    }

    /** Guaranteed Netherite Upgrade Smithing Template. */
    public static ItemStack createNetheriteUpgrade() {
        return new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1);
    }

    /** One random armor trim template from the config list, or null if none are valid. */
    public static ItemStack rollTrim(FileConfiguration cfg, Random random) {
        List<Material> valid = new ArrayList<>();
        for (String name : cfg.getStringList("trims")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                valid.add(material);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        return new ItemStack(valid.get(random.nextInt(valid.size())), 1);
    }

    /**
     * Each loot entry rolls its chance, then produces several separate stacks.
     * Config keys: material, chance, stacks-min, stacks-max, amount-min, amount-max.
     */
    public static List<ItemStack> rollLoot(FileConfiguration cfg, Random random) {
        List<ItemStack> items = new ArrayList<>();
        ConfigurationSection loot = cfg.getConfigurationSection("loot");
        if (loot == null) {
            return items;
        }
        for (String key : loot.getKeys(false)) {
            ConfigurationSection entry = loot.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            Material material = Material.matchMaterial(entry.getString("material", ""));
            if (material == null || material == Material.OBSIDIAN) {
                continue;
            }
            if (random.nextDouble() > entry.getDouble("chance", 1.0)) {
                continue;
            }
            int stacks = between(random, entry.getInt("stacks-min", 1), entry.getInt("stacks-max", 1));
            int amountMin = Math.max(1, entry.getInt("amount-min", 1));
            int amountMax = Math.max(amountMin, entry.getInt("amount-max", amountMin));
            int cap = material.getMaxStackSize();
            for (int i = 0; i < stacks; i++) {
                int amount = Math.min(cap, between(random, amountMin, amountMax));
                items.add(new ItemStack(material, amount));
            }
        }
        return items;
    }

    private static int between(Random random, int min, int max) {
        int lo = Math.max(0, min);
        int hi = Math.max(lo, max);
        return lo + random.nextInt(hi - lo + 1);
    }
}
