package com.shulfur.hunt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
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
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

public final class TreasureListener implements Listener {

    private final HiddenTreasuresPlugin plugin;
    private final TreasureManager treasures;

    public TreasureListener(HiddenTreasuresPlugin plugin, TreasureManager treasures) {
        this.plugin = plugin;
        this.treasures = treasures;
    }

    // ------------------------------------------------------------------
    // Claiming. A hidden chest never opens; right-click claims it once.
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onChestInteract(PlayerInteractEvent event) {
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

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!treasures.isRunning()) {
            plugin.send(player, "not-running");
            return;
        }

        List<ItemStack> rewards = treasures.claim(treasure, block, player.getName());
        boolean overflow = false;
        for (ItemStack item : rewards) {
            Map<Integer, ItemStack> left = player.getInventory().addItem(item);
            if (!left.isEmpty()) {
                overflow = true;
                left.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
            }
        }
        if (overflow) {
            plugin.send(player, "claim-dropped");
        }
        plugin.send(player, "claim-self");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        plugin.onTreasureClaimed(player);
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
        if (holder instanceof BlockState state && treasures.isProtected(state.getBlock())) {
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
