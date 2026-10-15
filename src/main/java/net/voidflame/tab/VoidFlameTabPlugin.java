package net.voidflame.tab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class VoidFlameTabPlugin extends JavaPlugin implements Listener {
    private TabManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new TabManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("vftab");
        if (command == null) throw new IllegalStateException("Missing /vftab command declaration.");
        command.setExecutor(this);
        long period = Math.max(20L, getConfig().getLong("settings.update-interval-ticks", 40L));
        getServer().getScheduler().runTaskTimer(this, manager::updateAll, period, period);
        getServer().getScheduler().runTaskTimer(this, manager::refreshStatsAll, 200L, 200L);
        getServer().getScheduler().runTask(this, manager::refreshStatsAll);
        getLogger().info("VoidFlame-TAB enabled | SMP scoreboard and staff-star tablist");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> manager.refreshStats(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "toggle" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (action.equals("toggle")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only. Use /vftab reload from console.");
                return true;
            }
            manager.toggleScoreboard(player);
            return true;
        }
        if (action.equals("reload")) {
            if (!sender.hasPermission("voidflame.tab.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            reloadConfig();
            manager.resetBoards();
            manager.updateAll();
            manager.refreshStatsAll();
            sender.sendMessage("§aVoidFlame TAB/Scoreboard reloaded.");
            return true;
        }
        sender.sendMessage("§cUsage: /vftab <toggle|reload>");
        return true;
    }
}

final class TabManager {
    private static final String STATS_QUERY = """
        SELECT COALESCE(s.kills,0) AS kills,
               COALESCE(s.deaths,0) AS deaths,
               COALESCE(s.playtime_seconds,0) AS playtime_seconds,
               COALESCE(e.balance,0) AS balance,
               COALESCE(e.shards,0) AS shards,
               COALESCE((SELECT preference_value FROM player_preferences
                         WHERE uuid=? AND preference_key='scoreboard_hidden'), 'false') AS scoreboard_hidden
        FROM player_profiles_smp s
        LEFT JOIN economy_accounts e ON e.uuid=s.uuid
        WHERE s.uuid=?
        """;

    private final VoidFlameTabPlugin plugin;
    private final org.bukkit.scoreboard.ScoreboardManager scoreboardManager;
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> oldLines = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();

    TabManager(VoidFlameTabPlugin plugin) {
        this.plugin = plugin;
        this.scoreboardManager = Bukkit.getScoreboardManager();
    }

    void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }

    void refreshStatsAll() {
        for (Player player : Bukkit.getOnlinePlayers()) refreshStats(player);
    }

    void refreshStats(Player player) {
        if (player == null || !player.isOnline() || !loading.add(player.getUniqueId())) return;
        try {
            Plugin core = Bukkit.getPluginManager().getPlugin("VoidFlame-Core");
            if (core == null || !core.isEnabled()) {
                loading.remove(player.getUniqueId());
                stats.putIfAbsent(player.getUniqueId(), Stats.empty());
                return;
            }
            Object database = core.getClass().getMethod("database").invoke(core);
            Method query = database.getClass().getMethod("query", String.class, Object[].class);
            Object raw = query.invoke(database, STATS_QUERY,
                    (Object) new Object[]{player.getUniqueId().toString(), player.getUniqueId().toString()});
            if (!(raw instanceof CompletableFuture<?> future)) {
                loading.remove(player.getUniqueId());
                return;
            }
            future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(player.getUniqueId());
                if (!player.isOnline()) return;
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Unable to refresh SMP scoreboard data for " + player.getName(), error);
                    stats.putIfAbsent(player.getUniqueId(), Stats.empty());
                    update(player);
                    return;
                }
                if (result instanceof List<?> rows && !rows.isEmpty() && rows.getFirst() instanceof Map<?, ?> row) {
                    stats.put(player.getUniqueId(), new Stats(
                            number(row.get("balance")), number(row.get("shards")),
                            number(row.get("kills")), number(row.get("deaths")),
                            number(row.get("playtime_seconds")),
                            Boolean.parseBoolean(String.valueOf(row.get("scoreboard_hidden")))));
                } else {
                    stats.putIfAbsent(player.getUniqueId(), Stats.empty());
                }
                update(player);
            }));
        } catch (Exception error) {
            loading.remove(player.getUniqueId());
            plugin.getLogger().log(Level.WARNING, "Unable to access VoidFlame-Core database for SMP scoreboard.", error);
            stats.putIfAbsent(player.getUniqueId(), Stats.empty());
        }
    }

    void remove(UUID playerId) {
        stats.remove(playerId);
        boards.remove(playerId);
        oldLines.remove(playerId);
        loading.remove(playerId);
    }

    void resetBoards() {
        boards.clear();
        oldLines.clear();
    }

    void toggleScoreboard(Player player) {
        UUID id = player.getUniqueId();
        Stats current = stats.getOrDefault(id, Stats.empty());
        boolean hidden = !current.scoreboardHidden;
        CorePreferenceWriter.write(plugin, id, hidden).whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        player.sendMessage("§cCould not save your scoreboard preference. Try again.");
                        plugin.getLogger().log(Level.WARNING, "Unable to save scoreboard preference for " + player.getName(), error);
                        return;
                    }
                    Stats old = stats.getOrDefault(id, Stats.empty());
                    stats.put(id, old.withScoreboardHidden(hidden));
                    if (hidden && scoreboardManager != null) {
                        player.setScoreboard(scoreboardManager.getMainScoreboard());
                    } else {
                        update(player);
                    }
                    player.sendMessage(hidden ? "§7SMP scoreboard hidden." : "§aSMP scoreboard enabled.");
                }));
    }

    void update(Player player) {
        if (scoreboardManager == null || !player.isOnline()) return;
        Stats data = stats.getOrDefault(player.getUniqueId(), Stats.empty());
        if (!plugin.getConfig().getBoolean("settings.scoreboard-enabled", true) || data.scoreboardHidden) {
            player.setScoreboard(scoreboardManager.getMainScoreboard());
        } else {
            Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), ignored -> scoreboardManager.getNewScoreboard());
            String title = color(plugin.getConfig().getString("scoreboard.smp.title", "&5&lVOIDFLAME SMP"));
            Objective objective = board.getObjective("vf_smp");
            if (objective == null) {
                objective = board.registerNewObjective("vf_smp", Criteria.DUMMY,
                        LegacyComponentSerializer.legacySection().deserialize(title));
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            for (String previous : oldLines.getOrDefault(player.getUniqueId(), List.of())) {
                board.resetScores(previous);
            }
            List<String> lines = plugin.getConfig().getStringList("scoreboard.smp.lines");
            List<String> renderedLines = new ArrayList<>();
            Set<String> used = new HashSet<>();
            int score = lines.size();
            for (String line : lines) {
                String rendered = render(line, player, data);
                int max = Math.max(1, plugin.getConfig().getInt("settings.max-line-length", 40));
                if (rendered.length() > max) rendered = rendered.substring(0, max);
                while (!used.add(rendered)) rendered += ChatColor.RESET;
                rendered = rendered.isBlank() ? " " : rendered;
                renderedLines.add(rendered);
                objective.getScore(rendered).setScore(score--);
            }
            oldLines.put(player.getUniqueId(), List.copyOf(renderedLines));
            player.setScoreboard(board);
        }
        if (plugin.getConfig().getBoolean("settings.tab-enabled", true)) updateTab(player);
    }

    private String render(String line, Player player, Stats data) {
        long hours = data.playtimeSeconds / 3600L;
        long minutes = (data.playtimeSeconds % 3600L) / 60L;
        return color(line)
                .replace("%server_online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%player_name%", player.getName())
                .replace("%player_ping%", String.valueOf(Math.max(0, player.getPing())))
                .replace("%balance%", format(data.balance))
                .replace("%shards%", format(data.shards))
                .replace("%kills%", format(data.kills))
                .replace("%deaths%", format(data.deaths))
                .replace("%playtime%", hours + "h " + minutes + "m");
    }

    private void updateTab(Player player) {
        String header = color(plugin.getConfig().getString("tab.header", "&5&lVOIDFLAME SMP"));
        String footer = color(plugin.getConfig().getString("tab.footer", "&7Online: &f%server_online% &8• &7Ping: &f%player_ping%ms"))
                .replace("%server_online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%player_ping%", String.valueOf(Math.max(0, player.getPing())));
        var legacy = LegacyComponentSerializer.legacySection();
        player.sendPlayerListHeaderAndFooter(legacy.deserialize(header), legacy.deserialize(footer));
        player.playerListName(legacy.deserialize(staffDisplay(player)));
    }

    private String staffDisplay(Player player) {
        if (!player.hasPermission("voidflame.tab.staff") && !player.hasPermission("voidflame.staff") && !player.isOp()) {
            return "§f" + player.getName();
        }
        String starColor = player.hasPermission("voidflame.staff.owner") ? "§d"
                : player.hasPermission("voidflame.staff.manager") ? "§6"
                : player.hasPermission("voidflame.staff.admin") ? "§c"
                : player.hasPermission("voidflame.staff.moderator") ? "§9" : "§b";
        return starColor + "★ §f" + player.getName();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String format(long value) {
        return java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value);
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private record Stats(long balance, long shards, long kills, long deaths, long playtimeSeconds, boolean scoreboardHidden) {
        static Stats empty() { return new Stats(0, 0, 0, 0, 0, false); }
        Stats withScoreboardHidden(boolean hidden) { return new Stats(balance, shards, kills, deaths, playtimeSeconds, hidden); }
    }
}

final class CorePreferenceWriter {
    private CorePreferenceWriter() {}

    static CompletableFuture<Void> write(JavaPlugin plugin, UUID playerId, boolean hidden) {
        try {
            Plugin core = Bukkit.getPluginManager().getPlugin("VoidFlame-Core");
            if (core == null || !core.isEnabled()) throw new IllegalStateException("VoidFlame-Core unavailable");
            Object database = core.getClass().getMethod("database").invoke(core);
            Method execute = database.getClass().getMethod("execute", String.class, Object[].class);
            Object raw = execute.invoke(database,
                    "INSERT INTO player_preferences(uuid,preference_key,preference_value,updated_at) VALUES (?,'scoreboard_hidden',?,?) ON CONFLICT(uuid,preference_key) DO UPDATE SET preference_value=excluded.preference_value,updated_at=excluded.updated_at",
                    (Object) new Object[]{playerId.toString(), Boolean.toString(hidden), System.currentTimeMillis()});
            if (raw instanceof CompletableFuture<?> future) return future.thenApply(ignored -> null);
            throw new IllegalStateException("Unexpected Core database result.");
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
