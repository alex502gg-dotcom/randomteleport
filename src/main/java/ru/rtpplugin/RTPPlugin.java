package ru.rtpplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class RTPPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> warmups = new HashMap<>();
    private final Map<UUID, Location> lastTeleports = new HashMap<>();
    private final Set<UUID> searchingPlayers = ConcurrentHashMap.newKeySet();
    private File guiFile;
    private FileConfiguration guiConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupGuiConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("rtp") != null) {
            getCommand("rtp").setExecutor(this);
            getCommand("rtp").setTabCompleter(this);
        }
    }

    private void setupGuiConfig() {
        File guiFolder = new File(getDataFolder(), "gui");
        if (!guiFolder.exists()) {
            guiFolder.mkdirs();
        }

        guiFile = new File(guiFolder, "gui.yml");
        if (!guiFile.exists()) {
            saveResource("gui/gui.yml", false);
        }
        guiConfig = YamlConfiguration.loadConfiguration(guiFile);
    }

    private void reloadAll() {
        reloadConfig();
        setupGuiConfig();
        cooldowns.clear();
        lastTeleports.clear();
        cancelAllWarmups();
        searchingPlayers.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color(message("only-player")));
                return true;
            }
            if (!hasPermission(player, "rtp.use")) {
                return true;
            }
            openMenu(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "low" -> runTeleportCommand(sender, TeleportType.LOW, "rtp.low");
            case "high" -> runTeleportCommand(sender, TeleportType.HIGH, "rtp.high");
            case "near" -> runTeleportCommand(sender, TeleportType.NEAR, "rtp.near");
            case "help" -> sendHelp(sender);
            case "reload" -> reloadPlugin(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void runTeleportCommand(CommandSender sender, TeleportType type, String permission) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(message("only-player")));
            return;
        }
        if (!hasPermission(player, permission)) {
            return;
        }
        teleport(player, type);
    }

    private void sendHelp(CommandSender sender) {
        if (!hasPermission(sender, "rtp.help")) {
            return;
        }
        for (String line : getConfig().getStringList("messages.help")) {
            sender.sendMessage(color(line));
        }
    }

    private void reloadPlugin(CommandSender sender) {
        if (!hasPermission(sender, "rtp.reload")) {
            return;
        }
        reloadAll();
        sender.sendMessage(color(message("reloaded")));
    }

    private void openMenu(Player player) {
        int size = guiConfig.getInt("menu.size", 9);
        size = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
        Inventory inventory = Bukkit.createInventory(null, size, color(guiConfig.getString("menu.title", "&6RTP")));

        addMenuItem(inventory, "low");
        addMenuItem(inventory, "high");
        addMenuItem(inventory, "near");

        player.openInventory(inventory);
    }

    private void addMenuItem(Inventory inventory, String key) {
        String path = "items." + key + ".";
        int slot = guiConfig.getInt(path + "slot", -1);
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        Material material = Material.matchMaterial(guiConfig.getString(path + "material", "STONE"));
        if (material == null) {
            material = Material.STONE;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(guiConfig.getString(path + "name", key)));
            meta.setLore(guiConfig.getStringList(path + "lore").stream().map(this::color).collect(Collectors.toList()));
            if (guiConfig.getBoolean(path + "enchanted", false)) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!event.getView().getTitle().equals(color(guiConfig.getString("menu.title", "&6RTP")))) {
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot == guiConfig.getInt("items.low.slot", -1)) {
            player.closeInventory();
            if (hasPermission(player, "rtp.low")) {
                teleport(player, TeleportType.LOW);
            }
        } else if (slot == guiConfig.getInt("items.high.slot", -1)) {
            player.closeInventory();
            if (hasPermission(player, "rtp.high")) {
                teleport(player, TeleportType.HIGH);
            }
        } else if (slot == guiConfig.getInt("items.near.slot", -1)) {
            player.closeInventory();
            if (hasPermission(player, "rtp.near")) {
                teleport(player, TeleportType.NEAR);
            }
        }
    }

    private void teleport(Player player, TeleportType type) {
        int cooldownSeconds = getConfig().getInt("teleport.cooldown-seconds", 10);
        long now = System.currentTimeMillis();
        long availableAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (availableAt > now) {
            long left = Math.max(1, (availableAt - now + 999) / 1000);
            player.sendMessage(color(prefix() + message("cooldown").replace("%seconds%", String.valueOf(left))));
            return;
        }

        if (warmups.containsKey(player.getUniqueId()) || !searchingPlayers.add(player.getUniqueId())) {
            player.sendMessage(color(prefix() + message("already-searching")));
            return;
        }

        CompletableFuture<Location> search = findTeleportLocation(player, type);
        search.whenComplete((location, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
            searchingPlayers.remove(player.getUniqueId());
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null || location == null) {
                player.sendMessage(color(prefix() + message("search-failed")));
                return;
            }

            final Location targetLocation = location;
            beginWarmupTeleport(player, targetLocation, cooldownSeconds);
        }));
    }

    private void beginWarmupTeleport(Player player, Location targetLocation, int cooldownSeconds) {
        int warmupSeconds = Math.max(0, getConfig().getInt("teleport.warmup-seconds", 5));
        if (warmupSeconds <= 0) {
            teleportNow(player, targetLocation, cooldownSeconds);
            return;
        }

        player.sendMessage(color(prefix() + message("warmup").replace("%seconds%", String.valueOf(warmupSeconds))));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            warmups.remove(player.getUniqueId());
            if (player.isOnline()) {
                teleportNow(player, targetLocation, cooldownSeconds);
            }
        }, warmupSeconds * 20L);
        warmups.put(player.getUniqueId(), task);
    }

    private void teleportNow(Player player, Location targetLocation, int cooldownSeconds) {
        player.teleportAsync(targetLocation).thenAccept(success -> Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!success) {
                player.sendMessage(color(prefix() + message("search-failed")));
                return;
            }
            lastTeleports.put(player.getUniqueId(), targetLocation);
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
            player.sendMessage(color(prefix() + message("teleported")
                    .replace("%x%", String.valueOf(targetLocation.getBlockX()))
                    .replace("%y%", String.valueOf(targetLocation.getBlockY()))
                    .replace("%z%", String.valueOf(targetLocation.getBlockZ()))));
        }));
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!warmups.containsKey(event.getPlayer().getUniqueId()) || event.getTo() == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!from.getWorld().equals(to.getWorld())) {
            cancelWarmup(event.getPlayer(), message("cancelled-move"));
            return;
        }
        if (from.distanceSquared(to) <= 0.0001) {
            return;
        }
        cancelWarmup(event.getPlayer(), message("cancelled-move"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelWarmup(player, message("cancelled-damage"));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelWarmup(event.getPlayer(), null);
        searchingPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void cancelWarmup(Player player, String cancelMessage) {
        BukkitTask task = warmups.remove(player.getUniqueId());
        if (task == null) {
            return;
        }
        task.cancel();
        if (cancelMessage != null && player.isOnline()) {
            player.sendMessage(color(prefix() + cancelMessage));
        }
    }

    private void cancelAllWarmups() {
        for (BukkitTask task : warmups.values()) {
            task.cancel();
        }
        warmups.clear();
    }

    private CompletableFuture<Location> randomLocationAsync(Player player, int minRadius, int maxRadius) {
        World world = getTeleportWorld(player);
        Location center = player.getLocation();
        return randomLocationAroundAsync(player, world, center, minRadius, maxRadius);
    }

    private CompletableFuture<Location> findTeleportLocation(Player player, TeleportType type) {
        return findTeleportLocation(player, type, 0);
    }

    private CompletableFuture<Location> findTeleportLocation(Player player, TeleportType type, int round) {
        int maxRounds = Math.max(1, getConfig().getInt("teleport.search-rounds", 8));
        if (round >= maxRounds) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Location> search = switch (type) {
            case LOW -> randomLocationAsync(player, getConfig().getInt("teleport.low.min-radius", 400), getConfig().getInt("teleport.low.max-radius", 500));
            case HIGH -> randomLocationAsync(player, getConfig().getInt("teleport.high.min-radius", 1000), getConfig().getInt("teleport.high.max-radius", 5000));
            case NEAR -> findNearPlayerLocationAsync(player);
        };

        return search.thenCompose(location -> location != null
                ? CompletableFuture.completedFuture(location)
                : findTeleportLocation(player, type, round + 1));
    }

    private CompletableFuture<Location> findNearPlayerLocationAsync(Player player) {
        int radius = getConfig().getInt("teleport.near.radius", 10000);
        World teleportWorld = getTeleportWorld(player);
        List<Player> targets = Bukkit.getOnlinePlayers().stream()
                .filter(target -> !target.equals(player))
                .filter(target -> target.getWorld().equals(teleportWorld))
                .collect(Collectors.toCollection(ArrayList::new));

        if (targets.isEmpty()) {
            player.sendMessage(color(prefix() + message("no-targets")));
            return CompletableFuture.completedFuture(null);
        }

        Collections.shuffle(targets);
        int minDistance = getConfig().getInt("teleport.near.min-distance-from-target", 16);
        Player target = targets.get(0);
        return randomLocationAroundAsync(player, target.getWorld(), target.getLocation(), minDistance, radius);
    }

    private CompletableFuture<Location> randomLocationAroundAsync(Player player, World world, Location center, int minRadius, int maxRadius) {
        return randomLocationAroundAttemptAsync(player, world, center, minRadius, maxRadius, getConfig().getInt("teleport.attempts", 80));
    }

    private CompletableFuture<Location> randomLocationAroundAttemptAsync(Player player, World world, Location center, int minRadius, int maxRadius, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            return CompletableFuture.completedFuture(null);
        }

        int min = Math.max(0, Math.min(minRadius, maxRadius));
        int max = Math.max(min + 1, Math.max(minRadius, maxRadius));
        double angle = random.nextDouble() * Math.PI * 2;
        int distance = min + random.nextInt(max - min + 1);
        int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
        int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
        return locationAtAsync(world, x, z).thenCompose(location -> {
            if (location != null && !isSameAsLastTeleport(player, location)) {
                return CompletableFuture.completedFuture(location);
            }
            return randomLocationAroundAttemptAsync(player, world, center, minRadius, maxRadius, attemptsLeft - 1);
        });
    }

    private CompletableFuture<Location> locationAtAsync(World world, int x, int z) {
        return world.getChunkAtAsync(x >> 4, z >> 4, true)
                .thenCompose(chunk -> {
                    CompletableFuture<Location> result = new CompletableFuture<>();
                    Bukkit.getScheduler().runTask(this, () -> result.complete(locationAt(world, x, z)));
                    return result;
                });
    }

    private Location locationAt(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        if (y <= world.getMinHeight()) {
            return null;
        }
        Block ground = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);

        if (!ground.getType().isSolid()) {
            return null;
        }
        if (ground.isLiquid() || feet.isLiquid() || head.isLiquid()) {
            return null;
        }
        if (!feet.isPassable() || !head.isPassable()) {
            return null;
        }

        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private boolean isSameAsLastTeleport(Player player, Location candidate) {
        Location last = lastTeleports.get(player.getUniqueId());
        if (last == null) {
            return false;
        }
        if (!last.getWorld().equals(candidate.getWorld())) {
            return false;
        }
        double minDistance = getConfig().getDouble("teleport.different-from-last-distance", 64.0);
        return last.distanceSquared(candidate) < minDistance * minDistance;
    }

    private World getTeleportWorld(Player player) {
        String worldName = getConfig().getString("teleport.world", "");
        World configuredWorld = worldName == null || worldName.isBlank() ? null : Bukkit.getWorld(worldName);
        return configuredWorld == null ? player.getWorld() : configuredWorld;
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(color(prefix() + message("no-permission")));
        return false;
    }

    private String prefix() {
        return message("prefix");
    }

    private String message(String key) {
        return getConfig().getString("messages." + key, defaultMessage(key));
    }

    private String defaultMessage(String key) {
        return switch (key) {
            case "prefix" -> "&6[RTP]&r ";
            case "no-permission" -> "&cУ тебя нет прав на эту команду.";
            case "only-player" -> "&cЭта команда доступна только игроку.";
            case "reloaded" -> "&aПлагин перезагружен.";
            case "cooldown" -> "&cПодожди ещё &e%seconds% сек.&c перед следующей телепортацией.";
            case "warmup" -> "&eТелепортация через &6%seconds% &eсекунд. Не двигайся и не получай урон.";
            case "cancelled-move" -> "&cТелепортация отменена, ты сдвинулся.";
            case "cancelled-damage" -> "&cТелепортация отменена, ты получил урон.";
            case "teleported" -> "&aТы телепортирован на координаты &e%x% &a/&e %y% &a/&e %z%&a.";
            case "no-targets" -> "&cНет подходящих игроков для телепортации рядом.";
            case "already-searching" -> "&cТелепортация уже ищется, подожди немного.";
            case "search-failed" -> "&cРќРµ СѓРґР°Р»РѕСЃСЊ РЅР°Р№С‚Рё РЅРѕРІСѓСЋ С‚РѕС‡РєСѓ С‚РµР»РµРїРѕСЂС‚Р°С†РёРё. РџРѕРїСЂРѕР±СѓР№ РµС‰С‘ СЂР°Р·.";
            default -> "";
        };
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String start = args[0].toLowerCase();
        return Arrays.asList("low", "high", "near", "help", "reload").stream()
                .filter(option -> switch (option) {
                    case "low" -> sender.hasPermission("rtp.low");
                    case "high" -> sender.hasPermission("rtp.high");
                    case "near" -> sender.hasPermission("rtp.near");
                    case "help" -> sender.hasPermission("rtp.help");
                    case "reload" -> sender.hasPermission("rtp.reload");
                    default -> false;
                })
                .filter(option -> option.startsWith(start))
                .collect(Collectors.toList());
    }

    private enum TeleportType {
        LOW,
        HIGH,
        NEAR
    }
}
