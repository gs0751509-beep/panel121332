package com.shulfur.hunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builds the reward items placed in each treasure chest and rolls random loot. */
public final class TreasureItems {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private TreasureItems() {
    }

    /** The glowing Shulfur Core: the Obsidian Block every treasure contains. */
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
        item.setItemMeta(meta);
        return item;
    }

    /** Guaranteed Netherite Upgrade Smithing Template. */
    public static ItemStack createNetheriteUpgrade() {
        return new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1);
    }

    /** Picks one random armor trim template from the config list, or null if none are valid. */
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

    /** Rolls each configured loot entry independently based on its chance. */
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
                continue; // obsidian is reserved for the Core
            }
            if (random.nextDouble() > entry.getDouble("chance", 1.0)) {
                continue;
            }
            int min = Math.max(1, entry.getInt("min", 1));
            int max = Math.max(min, entry.getInt("max", min));
            items.add(new ItemStack(material, min + random.nextInt(max - min + 1)));
        }
        return items;
    }
}
