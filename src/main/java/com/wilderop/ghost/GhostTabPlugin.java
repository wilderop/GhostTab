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
        version = "1.2",
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

        // Periodic save so a hard kill / crash loses less data
        server.getScheduler()
                .buildTask(this, this::saveData)
                .repeat(5, TimeUnit.MINUTES)
                .schedule();

        logger.info("GhostTab v1.2 enabled. Offline window: {} hours, update every {}s",
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

        // Both lists sorted alphabetically (case-insensitive)
        Comparator<PlayerData> alpha = Comparator.comparing(d -> d.name.toLowerCase(Locale.ROOT));
        online.sort(alpha);
        offline.sort(alpha);

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

        // Clear everything and rebuild so order is consistent
        Set<UUID> currentEntries = tabList.getEntries().stream()
                .map(e -> e.getProfile().getId())
                .collect(Collectors.toSet());
        for (UUID uuid : currentEntries) {
            tabList.removeEntry(uuid);
        }

        // listOrder: higher number = higher in the list (Minecraft 1.21.2+)
        // Online players get 10000+, offline get lower values so they stay at the bottom.
        // Within each group we decrease the number so alphabetical order is preserved.
        int listOrder = 10000 + online.size();

        // --- Online players (top) ---
        for (PlayerData data : online) {
            listOrder--;

            Optional<Player> realPlayer = server.getPlayer(data.uuid);
            GameProfile profile;
            int latency = 0;

            if (realPlayer.isPresent()) {
                Player p = realPlayer.get();
                // Keep real skin, but use a sort-friendly name for older clients
                // Prefix "0" sorts before "1", so online appear above offline
                profile = new GameProfile(data.uuid, "0" + data.name, p.getGameProfile().getProperties());
                latency = (int) p.getPing();
            } else {
                profile = new GameProfile(data.uuid, "0" + data.name, List.of());
            }

            Instant since = data.onlineSince != null ? data.onlineSince : now;
            String timeStr = formatDuration(Duration.between(since, now));
            String display = onlineFormat
                    .replace("{name}", data.name)
                    .replace("{time}", timeStr);

            TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(profile)
                    .displayName(parse(display))
                    .latency(latency)
                    .gameMode(0)
                    .listOrder(listOrder)
                    .build();

            tabList.addEntry(entry);
        }

        // --- Offline / ghost players (bottom) ---
        listOrder = 1000 + offline.size(); // much lower than online
        for (PlayerData data : offline) {
            listOrder--;

            // Prefix "1" so older clients sort these after the "0..." online entries
            GameProfile profile = new GameProfile(data.uuid, "1" + data.name, List.of());

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
                    .listOrder(listOrder)
                    .build();

            tabList.addEntry(entry);
        }

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
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load config, using defaults", e);
        }
    }

    private void loadData() {
        Path dataFile = dataDirectory.resolve("playerdata.yml");
        if (!Files.exists(dataFile)) {
            logger.info("No existing playerdata.yml found");
            return;
        }

        try {
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(dataFile)) {
                Map<String, Object> raw = yaml.load(in);
                if (raw == null) {
                    logger.info("playerdata.yml was empty");
                    return;
                }

                Instant now = Instant.now();
                Instant cutoff = now.minusMillis(offlineWindowMillis);
                int loaded = 0;
                int skipped = 0;

                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) entry.getValue();
                        String name = String.valueOf(map.getOrDefault("name", "Unknown"));
                        Object lastSeenObj = map.get("lastSeen");
                        if (lastSeenObj == null) {
                            skipped++;
                            continue;
                        }
                        long lastSeenEpoch = ((Number) lastSeenObj).longValue();
                        Instant lastSeen = Instant.ofEpochSecond(lastSeenEpoch);

                        // Skip anyone already outside the offline window
                        if (lastSeen.isBefore(cutoff)) {
                            skipped++;
                            continue;
                        }

                        PlayerData data = new PlayerData(name);
                        data.uuid = uuid;
                        data.lastSeen = lastSeen;
                        data.online = false;
                        playerData.put(uuid, data);
                        loaded++;
                    } catch (Exception e) {
                        logger.warn("Skipping bad playerdata entry {}: {}", entry.getKey(), e.getMessage());
                        skipped++;
                    }
                }
                logger.info("Loaded {} player records ({} skipped as too old/invalid)", loaded, skipped);
            }
        } catch (Exception e) {
            logger.warn("Could not load player data", e);
        }
    }

    private void saveData() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Instant now = Instant.now();
            Instant cutoff = now.minusMillis(offlineWindowMillis);
            Map<String, Object> toSave = new LinkedHashMap<>();

            for (Map.Entry<UUID, PlayerData> entry : playerData.entrySet()) {
                PlayerData data = entry.getValue();

                // Players who are still online at shutdown must be treated as
                // having just gone offline *now*, otherwise their lastSeen stays
                // at login time and they appear offline for hours after reboot.
                Instant effectiveLastSeen = data.online ? now : data.lastSeen;

                if (data.online || effectiveLastSeen.isAfter(cutoff)) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", data.name);
                    map.put("lastSeen", effectiveLastSeen.getEpochSecond());
                    toSave.put(entry.getKey().toString(), map);
                }
            }

            Path dataFile = dataDirectory.resolve("playerdata.yml");
            Yaml yaml = new Yaml();
            Files.writeString(dataFile, yaml.dump(toSave));
            logger.info("Saved {} player records to disk", toSave.size());
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
