package com.shulfur.hunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class HiddenTreasuresPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    public static final String PERM_ADMIN = "shulfur.hunt.admin";

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> COMMANDS = List.of(
            "huntset", "huntclue", "huntstart", "huntstop", "huntstatus", "huntreset", "huntnether");

    private TreasureManager treasures;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        treasures = new TreasureManager(this);
        treasures.load();

        for (String name : COMMANDS) {
            PluginCommand cmd = getCommand(name);
            if (cmd == null) {
                getLogger().severe("Command missing from plugin.yml: " + name);
                continue;
            }
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        getServer().getPluginManager().registerEvents(new TreasureListener(this, treasures), this);
        getLogger().info("Hidden Treasures ready: " + treasures.collectedCount()
                + "/" + TreasureManager.TOTAL + " collected.");
    }

    @Override
    public void onDisable() {
        if (treasures != null) {
            treasures.save();
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "huntset" -> handleSet(sender, args);
            case "huntclue" -> handleClue(sender);
            case "huntstart" -> handleStart(sender);
            case "huntstop" -> handleStop(sender);
            case "huntstatus" -> handleStatus(sender);
            case "huntreset" -> handleReset(sender, args);
            case "huntnether" -> handleNether(sender, args);
            default -> false;
        };
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return true;
        }
        if (args.length < 2) {
            send(sender, "usage-set");
            return true;
        }
        Integer id = parseId(args[0]);
        if (id == null) {
            send(sender, "invalid-id", totalTag());
            return true;
        }
        if (treasures.isRunning()) {
            send(sender, "set-running");
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("remove")) {
            if (treasures.get(id) == null) {
                send(sender, "remove-none", idTag(id));
            } else {
                treasures.remove(id);
                send(sender, "remove-success", idTag(id));
            }
            return true;
        }

        Block target = player.getTargetBlockExact(5);
        if (target == null || !(target.getState() instanceof Chest chest)) {
            send(sender, "set-no-chest");
            return true;
        }
        if (chest.getInventory() instanceof DoubleChestInventory) {
            send(sender, "set-double-chest");
            return true;
        }
        TreasureManager.Treasure existing = treasures.at(target);
        if (existing != null && existing.id() != id) {
            send(sender, "set-duplicate", idTag(existing.id()));
            return true;
        }

        String clue = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (clue.length() > 160) {
            send(sender, "clue-too-long");
            return true;
        }
        treasures.place(id, target, clue);
        send(sender, "set-success", idTag(id));
        return true;
    }

    private boolean handleClue(CommandSender sender) {
        if (!treasures.isRunning() && !sender.hasPermission(PERM_ADMIN)) {
            send(sender, "not-running");
            return true;
        }
        TreasureManager.Treasure next = treasures.nextClue();
        if (next == null) {
            send(sender, "clue-none");
            return true;
        }
        send(sender, "clue-line", idTag(next.id()), Placeholder.unparsed("clue", next.clue()));
        return true;
    }

    private boolean handleStart(CommandSender sender) {
        if (treasures.isRunning()) {
            send(sender, "start-already");
            return true;
        }
        if (treasures.collectedCount() == TreasureManager.TOTAL) {
            send(sender, "start-done");
            return true;
        }
        if (!treasures.isComplete()) {
            List<Integer> missing = treasures.missingIds();
            send(sender, "start-missing",
                    Placeholder.unparsed("count", String.valueOf(TreasureManager.TOTAL - missing.size())),
                    totalTag(),
                    Placeholder.unparsed("missing", ids(missing)));
            return true;
        }
        treasures.setRunning(true);
        broadcast("start", true, progress());
        announceNextClue();
        return true;
    }

    private boolean handleStop(CommandSender sender) {
        if (!treasures.isRunning()) {
            send(sender, "stop-already");
            return true;
        }
        treasures.setRunning(false);
        broadcast("stop", true);
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        send(sender, "status-progress", progress());
        send(sender, "status-state", Placeholder.parsed("state",
                treasures.isRunning() ? "<green>RUNNING</green>" : "<red>STOPPED</red>"));
        send(sender, "status-nether", netherTag());
        if (sender.hasPermission(PERM_ADMIN)) {
            send(sender, "status-admin",
                    Placeholder.unparsed("missing", ids(treasures.missingIds())),
                    Placeholder.unparsed("remaining", ids(treasures.uncollectedIds())));
        }
        return true;
    }

    private boolean handleReset(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("confirm")) {
            send(sender, "reset-confirm");
            return true;
        }
        treasures.reset();          // stops the hunt, locks the Nether, refills chests
        evacuateNether();
        send(sender, "reset-done");
        broadcast("reset-notice", false);
        return true;
    }

    private boolean handleNether(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "usage-nether");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open" -> openNether(sender);
            case "lock" -> lockNether(sender);
            case "status" -> send(sender, "status-nether", netherTag());
            default -> send(sender, "usage-nether");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Event flow
    // ------------------------------------------------------------------

    /** Called by the listener after a treasure has been claimed and saved. */
    public void onTreasureClaimed(Player player) {
        int found = treasures.collectedCount();
        broadcast("claim-broadcast", false, progress(),
                Placeholder.unparsed("player", player.getName()));

        if (found == TreasureManager.TOTAL) {
            broadcast("complete", true);
            if (getConfig().getBoolean("nether.auto-open-on-completion", false)) {
                openNether(null);
            } else {
                broadcast("complete-manual", false);
            }
            return;
        }
        if (found == 3 || found == 6 || found == 9) {
            broadcast("milestone", true, progress());
        }
        announceNextClue();
    }

    public void openNether(CommandSender sender) {
        if (treasures.isNetherOpen()) {
            if (sender != null) {
                send(sender, "nether-already-open");
            }
            return;
        }
        treasures.setNetherOpen(true);
        if (!netherWorldLoaded()) {
            getLogger().warning("Nether opened in plugin state, but no Nether world is loaded.");
            if (sender != null) {
                send(sender, "nether-world-missing");
            }
        }
        broadcast("nether-open", true);
    }

    public void lockNether(CommandSender sender) {
        treasures.setNetherOpen(false);
        int moved = evacuateNether();
        if (sender != null) {
            send(sender, "nether-lock-done", Placeholder.unparsed("moved", String.valueOf(moved)));
        }
    }

    private void announceNextClue() {
        TreasureManager.Treasure next = treasures.nextClue();
        if (next != null) {
            broadcast("next-clue", false, idTag(next.id()), Placeholder.unparsed("clue", next.clue()));
        }
    }

    private boolean netherWorldLoaded() {
        return Bukkit.getWorlds().stream().anyMatch(w -> w.getEnvironment() == World.Environment.NETHER);
    }

    /** Moves every player currently in the Nether to the main overworld spawn. */
    private int evacuateNether() {
        Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
        int moved = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == World.Environment.NETHER) {
                player.teleport(spawn);
                moved++;
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("huntset")) {
            if (args.length == 1) {
                return IntStream.rangeClosed(1, TreasureManager.TOTAL).mapToObj(String::valueOf).toList();
            }
            if (args.length == 2) {
                return List.of("remove");
            }
        }
        if (name.equals("huntnether") && args.length == 1) {
            return List.of("open", "lock", "status");
        }
        if (name.equals("huntreset") && args.length == 1) {
            return List.of("confirm");
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Messaging helpers
    // ------------------------------------------------------------------

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(build(key, true, resolvers));
    }

    public void broadcast(String key, boolean sound, TagResolver... resolvers) {
        Component message = build(key, false, resolvers);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
            if (sound) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);
            }
        }
        getLogger().info(PlainTextComponentSerializer.plainText().serialize(message));
    }

    private Component build(String key, boolean withPrefix, TagResolver... resolvers) {
        String body = getConfig().getString("messages." + key, "<red>Missing message: " + key + "</red>");
        String text = withPrefix ? getConfig().getString("messages.prefix", "") + body : body;
        return MM.deserialize(text, resolvers);
    }

    private TagResolver progress() {
        return TagResolver.resolver(
                Placeholder.unparsed("count", String.valueOf(treasures.collectedCount())),
                totalTag());
    }

    private TagResolver totalTag() {
        return Placeholder.unparsed("total", String.valueOf(TreasureManager.TOTAL));
    }

    private static TagResolver idTag(int id) {
        return Placeholder.unparsed("id", String.valueOf(id));
    }

    private TagResolver netherTag() {
        return Placeholder.parsed("nether", treasures.isNetherOpen()
                ? "<green>OPEN</green>" : "<red>LOCKED</red>");
    }

    private static String ids(List<Integer> list) {
        if (list.isEmpty()) {
            return "none";
        }
        return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static Integer parseId(String text) {
        try {
            int id = Integer.parseInt(text);
            return (id >= 1 && id <= TreasureManager.TOTAL) ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
