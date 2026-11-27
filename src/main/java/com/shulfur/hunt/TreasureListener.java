package com.shulfur.hunt;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TreasureListener implements Listener {

    private final HiddenTreasuresPlugin plugin;
    private final TreasureManager treasures;
    private final File pendingFile;
    private final List<ItemStack> pending;

    public TreasureListener(HiddenTreasuresPlugin plugin, TreasureManager treasures) {
        this.plugin = plugin;
        this.treasures = treasures;
        this.pendingFile = new File(plugin.getDataFolder(), "pending.yml");
        this.pending = loadPending();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String targetName() {
        return plugin.getConfig().getString("prize.target-player", "GAuravxd");
    }

    private Material firstFinderMaterial() {
        return Material.matchMaterial(plugin.getConfig().getString("prize.first-finder-item", "FLINT_AND_STEEL"));
    }

    /** The prize player if online, otherwise null. Name match ignores case. */
    private Player findTarget() {
        String name = targetName();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private void deliver(Player target, List<ItemStack> items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> left = target.getInventory().addItem(item);
            left.values().forEach(stack -> target.getWorld().dropItemNaturally(target.getLocation(), stack));
        }
    }

    private List<ItemStack> loadPending() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(pendingFile);
        List<ItemStack> items = new ArrayList<>();
        for (Object o : yml.getList("items", new ArrayList<>())) {
            if (o instanceof ItemStack item) {
                items.add(item);
            }
        }
        return items;
    }

    private void savePending() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("items", pending);
        try {
            yml.save(pendingFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save pending.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Opening a treasure chest. The first opener gets the Flint & Steel.
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onChestOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CHEST) {
            return;
        }
        TreasureManager.Treasure treasure = treasures.at(block);
        if (treasure == null || treasure.isCollected()) {
            return;
        }

        Player player = event.getPlayer();
        if (!treasures.isRunning()) {
            event.setCancelled(true);
            plugin.send(player, "not-running");
            return;
        }

        if (!treasures.isFirstFinderRewarded()) {
            Material bonus = firstFinderMaterial();
            if (bonus != null) {
                treasures.setFirstFinderRewarded(true);
                deliver(player, List.of(new ItemStack(bonus, 1)));
                plugin.send(player, "first-finder-bonus");
            }
        }
    }

    // ------------------------------------------------------------------
    // Taking the Shulfur Core out of a treasure chest claims the treasure.
    // The Core goes to the prize player. Everything else stays for the finder.
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onChestTakeCore(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof Chest chest)) {
            return;
        }
        if (event.getRawSlot() != TreasureItems.CORE_SLOT) {
            return;
        }
        TreasureManager.Treasure treasure = treasures.at(chest.getBlock());
        if (treasure == null || treasure.isCollected()) {
            return;
        }
        if (!TreasureItems.isCore(event.getInventory().getItem(TreasureItems.CORE_SLOT))) {
            return;
        }

        // Cancel the click so the Core never reaches the finder's inventory.
        event.setCancelled(true);

        if (!treasures.isRunning()) {
            plugin.send(player, "not-running");
            return;
        }

        event.getInventory().setItem(TreasureItems.CORE_SLOT, null);
        treasures.markClaimed(treasure, player.getName());

        Player target = findTarget();
        if (target != null) {
            deliver(target, List.of(TreasureItems.createCore(plugin.getConfig())));
            plugin.send(target, "prize-received");
            plugin.send(player, "claim-sent-to-target", Placeholder.unparsed("target", target.getName()));
        } else {
            pending.add(TreasureItems.createCore(plugin.getConfig()));
            savePending();
            plugin.send(player, "claim-pending", Placeholder.unparsed("target", targetName()));
        }

        plugin.send(player, "claim-self");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        plugin.onTreasureClaimed(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (pending.isEmpty() || !player.getName().equalsIgnoreCase(targetName())) {
            return;
        }
        deliver(player, new ArrayList<>(pending));
        pending.clear();
        savePending();
        plugin.send(player, "prize-received");
    }

    // ------------------------------------------------------------------
    // Protection of unclaimed treasure chests
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!treasures.isProtected(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        plugin.send(event.getPlayer(), "chest-protected");
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(treasures::isProtected);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(treasures::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(treasures::isProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(treasures::isProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPull(InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getSource().getHolder();
        if (!(holder instanceof Chest chest)) {
            return;
        }
        if (treasures.isProtected(chest.getBlock())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Nether gate
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!blockedNether(event.getTo(), event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        plugin.send(event.getPlayer(), "nether-locked");
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (blockedNether(event.getTo(), null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!blockedNether(event.getTo(), event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        plugin.send(event.getPlayer(), "nether-locked");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (!treasures.isNetherOpen() && event.getWorld().getEnvironment() == World.Environment.NORMAL) {
            event.setCancelled(true);
        }
    }

    /** True if entering the Nether must be stopped for this destination and player. */
    private boolean blockedNether(Location to, Player player) {
        if (treasures.isNetherOpen() || to == null || to.getWorld() == null) {
            return false;
        }
        if (to.getWorld().getEnvironment() != World.Environment.NETHER) {
            return false;
        }
        return player == null || !player.hasPermission(HiddenTreasuresPlugin.PERM_ADMIN);
    }
}
