package com.wilderop.ghost;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Plugin(
        id = "ghosttab",
        name = "GhostTab",
        version = "1.0",
        description = "Shows online players with session time and recently offline players in the tab list",
        authors = {"wilderop"}
)
public class GhostTabPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private final Map<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Config values
    private long offlineWindowMillis = TimeUnit.HOURS.toMillis(24);
    private long updateIntervalSeconds = 30;
    private String headerTemplate = "<gold><bold>A Zombie Pigman Broke My Door</bold></gold>";
    private String footerTemplate = "<gray>Online: {online} | Ghosts: {ghosts}</gray>";
    private String onlineFormat = "<white>{name} <gray>{time}</gray>";
    private String offlineFormat = "<dark_gray>{name} <gray>offline {time}</gray>";
    private boolean sortOfflineByRecent = true;

    @Inject
    public GhostTabPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        loadData();

        // Schedule periodic tab list updates
        server.getScheduler()
                .buildTask(this, this::updateAllTabLists)
                .repeat(updateIntervalSeconds, TimeUnit.SECONDS)
                .schedule();

        logger.info("GhostTab v1.0 enabled. Offline window: {} hours, update every {}s",
                TimeUnit.MILLISECONDS.toHours(offlineWindowMillis), updateIntervalSeconds);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        saveData();
        logger.info("GhostTab data saved.");
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getUsername();

        PlayerData data = playerData.computeIfAbsent(uuid, k -> new PlayerData(uuid, name));
        data.name = name;
        data.uuid = uuid;
        data.onlineSince = Instant.now();
        data.lastSeen = Instant.now();
        data.online = true;

        // Force an immediate update so the joining player sees the current list
        server.getScheduler().buildTask(this, this::updateAllTabLists).delay(500, TimeUnit.MILLISECONDS).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerData data = playerData.get(uuid);
        if (data != null) {
            data.online = false;
            data.lastSeen = Instant.now();
            data.onlineSince = null;
        }

        // Clean up very old entries occasionally
        cleanOldEntries();
    }

    private void updateAllTabLists() {
        Instant now = Instant.now();
        cleanOldEntries();

        List<PlayerData> online = new ArrayList<>();
        List<PlayerData> offline = new ArrayList<>();

        for (PlayerData data : playerData.values()) {
            if (data.online) {
                online.add(data);
            } else {
                long offlineFor = Duration.between(data.lastSeen, now).toMillis();
                if (offlineFor <= offlineWindowMillis) {
                    offline.add(data);
                }
            }
        }

        // Sort online alphabetically by name
        online.sort(Comparator.comparing(d -> d.name.toLowerCase(Locale.ROOT)));

        // Sort offline
        if (sortOfflineByRecent) {
            offline.sort(Comparator.comparing((PlayerData d) -> d.lastSeen).reversed());
        } else {
            offline.sort(Comparator.comparing(d -> d.name.toLowerCase(Locale.ROOT)));
        }

        int onlineCount = online.size();
        int ghostCount = offline.size();

        Component header = parse(headerTemplate
                .replace("{online}", String.valueOf(onlineCount))
                .replace("{ghosts}", String.valueOf(ghostCount)));
        Component footer = parse(footerTemplate
                .replace("{online}", String.valueOf(onlineCount))
                .replace("{ghosts}", String.valueOf(ghostCount)));

        for (Player viewer : server.getAllPlayers()) {
            try {
                updateTabListFor(viewer, online, offline, header, footer, now);
            } catch (Exception e) {
                logger.warn("Failed to update tab list for {}", viewer.getUsername(), e);
            }
        }
    }

    private void updateTabListFor(Player viewer, List<PlayerData> online, List<PlayerData> offline,
                                  Component header, Component footer, Instant now) {
        TabList tabList = viewer.getTabList();

        // Clear existing entries that we manage (everything except the viewer themselves is safer to rebuild)
        // We remove all and re-add to keep it simple and consistent
        Set<UUID> currentEntries = tabList.getEntries().stream()
                .map(e -> e.getProfile().getId())
                .collect(Collectors.toSet());

        // Keep the viewer's own entry if present, but we'll rebuild everything for consistency
        for (UUID uuid : currentEntries) {
            tabList.removeEntry(uuid);
        }

        // Add online players
        for (PlayerData data : online) {
            Optional<Player> realPlayer = server.getPlayer(data.uuid);
            GameProfile profile;
            int latency = 0;
            int gameMode = 0;

            if (realPlayer.isPresent()) {
                Player p = realPlayer.get();
                profile = p.getGameProfile();
                latency = (int) p.getPing();
                // gameMode is not directly available on proxy easily; leave 0 (survival)
            } else {
                // Shouldn't happen for online, but fallback
                profile = new GameProfile(data.uuid, data.name, List.of());
            }

            String timeStr = formatDuration(Duration.between(data.onlineSince, now));
            String display = onlineFormat
                    .replace("{name}", data.name)
                    .replace("{time}", timeStr);

            TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(profile)
                    .displayName(parse(display))
                    .latency(latency)
                    .gameMode(gameMode)
                    .build();

            tabList.addEntry(entry);
        }

        // Add offline/ghost players
        for (PlayerData data : offline) {
            GameProfile profile = new GameProfile(data.uuid, data.name, List.of()); // no skin for ghosts

            String timeStr = formatDuration(Duration.between(data.lastSeen, now));
            String display = offlineFormat
                    .replace("{name}", data.name)
                    .replace("{time}", timeStr);

            TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(profile)
                    .displayName(parse(display))
                    .latency(0)
                    .gameMode(0)
                    .build();

            tabList.addEntry(entry);
        }

        // Set header/footer
        viewer.sendPlayerListHeaderAndFooter(header, footer);
    }

    private Component parse(String input) {
        try {
            return miniMessage.deserialize(input);
        } catch (Exception e) {
            // Fallback to legacy if MiniMessage fails
            return LegacyComponentSerializer.legacyAmpersand().deserialize(input);
        }
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        if (totalSeconds < 0) totalSeconds = 0;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else if (minutes > 0) {
            return minutes + "m";
        } else {
            return totalSeconds + "s";
        }
    }

    private void cleanOldEntries() {
        Instant cutoff = Instant.now().minusMillis(offlineWindowMillis);
        playerData.entrySet().removeIf(entry -> {
            PlayerData data = entry.getValue();
            return !data.online && data.lastSeen.isBefore(cutoff);
        });
    }

    private void loadConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configFile = dataDirectory.resolve("config.yml");
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                    }
                }
            }

            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(configFile)) {
                Map<String, Object> cfg = yaml.load(in);
                if (cfg != null) {
                    offlineWindowMillis = TimeUnit.HOURS.toMillis(
                            ((Number) cfg.getOrDefault("offline-window-hours", 24)).longValue());
                    updateIntervalSeconds = ((Number) cfg.getOrDefault("update-interval-seconds", 30)).longValue();
                    headerTemplate = String.valueOf(cfg.getOrDefault("header", headerTemplate));
                    footerTemplate = String.valueOf(cfg.getOrDefault("footer", footerTemplate));
                    onlineFormat = String.valueOf(cfg.getOrDefault("online-format", onlineFormat));
                    offlineFormat = String.valueOf(cfg.getOrDefault("offline-format", offlineFormat));
                    sortOfflineByRecent = Boolean.parseBoolean(String.valueOf(cfg.getOrDefault("sort-offline-by-recent", true)));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load config, using defaults", e);
        }
    }

    private void loadData() {
        Path dataFile = dataDirectory.resolve("playerdata.yml");
        if (!Files.exists(dataFile)) return;

        try {
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(dataFile)) {
                Map<String, Object> raw = yaml.load(in);
                if (raw == null) return;

                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) entry.getValue();
                        String name = String.valueOf(map.getOrDefault("name", "Unknown"));
                        long lastSeenEpoch = ((Number) map.getOrDefault("lastSeen", Instant.now().getEpochSecond())).longValue();
                        PlayerData data = new PlayerData(name);
                        data.uuid = uuid;
                        data.lastSeen = Instant.ofEpochSecond(lastSeenEpoch);
                        data.online = false;
                        playerData.put(uuid, data);
                    } catch (Exception ignored) {
                    }
                }
            }
            logger.info("Loaded {} player records", playerData.size());
        } catch (Exception e) {
            logger.warn("Could not load player data", e);
        }
    }

    private void saveData() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Map<String, Object> toSave = new LinkedHashMap<>();
            Instant cutoff = Instant.now().minusMillis(offlineWindowMillis);

            for (Map.Entry<UUID, PlayerData> entry : playerData.entrySet()) {
                PlayerData data = entry.getValue();
                if (data.online || data.lastSeen.isAfter(cutoff)) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", data.name);
                    map.put("lastSeen", data.lastSeen.getEpochSecond());
                    toSave.put(entry.getKey().toString(), map);
                }
            }

            Path dataFile = dataDirectory.resolve("playerdata.yml");
            Yaml yaml = new Yaml();
            Files.writeString(dataFile, yaml.dump(toSave));
        } catch (IOException e) {
            logger.error("Failed to save player data", e);
        }
    }

    private static class PlayerData {
        UUID uuid;
        String name;
        Instant onlineSince;
        Instant lastSeen;
        boolean online;

        PlayerData(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
            this.lastSeen = Instant.now();
            this.online = false;
        }

        // For loading from disk
        PlayerData(String name) {
            this.name = name;
            this.lastSeen = Instant.now();
            this.online = false;
        }
    }
}
