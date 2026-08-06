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
        version = "1.4",
        description = "Shows online players with session time and recently offline players in the tab list",
        authors = {"wilderop"}
)
public class GhostTabPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private final Map<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    // Completed play sessions within the playtime window: [startEpochSeconds, endEpochSeconds]
    private final List<long[]> recentSessions = Collections.synchronizedList(new ArrayList<>());
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Config values
    private long offlineWindowMillis = TimeUnit.HOURS.toMillis(24);
    private long playtimeWindowMillis = TimeUnit.HOURS.toMillis(12);
    private long updateIntervalSeconds = 30;
    private String headerTemplate = "<gold><bold>A Zombie Pigman Broke My Door</bold></gold>";
    private String footerTemplate = "<gray>Players have played a total of <white>{total_hours}</white> hours in the last {playtime_window} hours</gray>";
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
        loadPlaytime();

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

        logger.info("GhostTab v1.4 enabled. Offline window: {}h, playtime window: {}h, update every {}s",
                TimeUnit.MILLISECONDS.toHours(offlineWindowMillis),
                TimeUnit.MILLISECONDS.toHours(playtimeWindowMillis),
                updateIntervalSeconds);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // Checkpoint current online sessions before saving
        checkpointOnlineSessions();
        saveData();
        savePlaytime();
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
            Instant now = Instant.now();
            if (data.online && data.onlineSince != null) {
                recordSession(data.onlineSince, now);
            }
            data.online = false;
            data.lastSeen = now;
            data.onlineSince = null;
        }

        // Clean up very old entries occasionally
        cleanOldEntries();
        cleanOldSessions();
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
        String totalHours = formatTotalHours(computePlaytimeSeconds(now));
        String playtimeWindowHours = String.valueOf(TimeUnit.MILLISECONDS.toHours(playtimeWindowMillis));

        Component header = parse(headerTemplate
                .replace("{online}", String.valueOf(onlineCount))
                .replace("{ghosts}", String.valueOf(ghostCount))
                .replace("{total_hours}", totalHours)
                .replace("{playtime_window}", playtimeWindowHours));
        Component footer = parse(footerTemplate
                .replace("{online}", String.valueOf(onlineCount))
                .replace("{ghosts}", String.valueOf(ghostCount))
                .replace("{total_hours}", totalHours)
                .replace("{playtime_window}", playtimeWindowHours));

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

        // UUIDs of players currently online on the proxy.
        // NEVER remove or recreate these — Velocity/backend own them.
        // Clearing + re-adding with modified profiles causes malformed packets
        // and kicks (especially with ViaVersion).
        Set<UUID> onlineUuids = server.getAllPlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());

        // Desired ghost UUIDs this tick
        Set<UUID> desiredGhosts = offline.stream()
                .map(d -> d.uuid)
                .filter(uuid -> uuid != null && !onlineUuids.contains(uuid))
                .collect(Collectors.toSet());

        // Remove only stale ghost entries (not online players)
        for (TabListEntry existing : new ArrayList<>(tabList.getEntries())) {
            UUID id = existing.getProfile().getId();
            if (!onlineUuids.contains(id) && !desiredGhosts.contains(id)) {
                tabList.removeEntry(id);
            }
        }

        // --- Online players: only update display name / listOrder in place ---
        int onlineOrder = 10000 + online.size();
        for (PlayerData data : online) {
            onlineOrder--;
            if (data.uuid == null) continue;

            Optional<TabListEntry> existingOpt = tabList.getEntry(data.uuid);
            if (existingOpt.isEmpty()) {
                // Not present yet (very early join) — leave it to Velocity
                continue;
            }

            TabListEntry existing = existingOpt.get();
            Instant since = data.onlineSince != null ? data.onlineSince : now;
            String timeStr = formatDuration(Duration.between(since, now));
            String display = onlineFormat
                    .replace("{name}", data.name)
                    .replace("{time}", timeStr);

            existing.setDisplayName(parse(display));
            try {
                existing.setListOrder(onlineOrder);
            } catch (Throwable ignored) {
                // listOrder only on 1.21.2+
            }
        }

        // --- Ghost / offline players: add or update ---
        int ghostOrder = 1000 + offline.size();
        for (PlayerData data : offline) {
            ghostOrder--;
            if (data.uuid == null || onlineUuids.contains(data.uuid)) continue;

            String timeStr = formatDuration(Duration.between(data.lastSeen, now));
            String display = offlineFormat
                    .replace("{name}", data.name)
                    .replace("{time}", timeStr);
            Component displayComponent = parse(display);

            Optional<TabListEntry> existingOpt = tabList.getEntry(data.uuid);
            if (existingOpt.isPresent()) {
                TabListEntry existing = existingOpt.get();
                existing.setDisplayName(displayComponent);
                try {
                    existing.setListOrder(ghostOrder);
                } catch (Throwable ignored) {
                }
            } else {
                // Real name, empty properties (default skin). No name prefix hacks.
                GameProfile profile = new GameProfile(data.uuid, data.name, List.of());

                TabListEntry.Builder builder = TabListEntry.builder()
                        .tabList(tabList)
                        .profile(profile)
                        .displayName(displayComponent)
                        .latency(0)
                        .gameMode(0);

                try {
                    builder.listOrder(ghostOrder);
                } catch (Throwable ignored) {
                }

                tabList.addEntry(builder.build());
            }
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
                    playtimeWindowMillis = TimeUnit.HOURS.toMillis(
                            ((Number) cfg.getOrDefault("playtime-window-hours", 12)).longValue());
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

            // Also checkpoint playtime on periodic saves
            checkpointOnlineSessions();
            savePlaytime();
        } catch (IOException e) {
            logger.error("Failed to save player data", e);
        }
    }

    /** Record a completed play session [start, end). */
    private void recordSession(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return;
        }
        long startSec = start.getEpochSecond();
        long endSec = end.getEpochSecond();
        if (endSec <= startSec) {
            return;
        }
        recentSessions.add(new long[]{startSec, endSec});
        cleanOldSessions();
    }

    /** Flush currently-online sessions into the session list and restart their counters. */
    private void checkpointOnlineSessions() {
        Instant now = Instant.now();
        for (PlayerData data : playerData.values()) {
            if (data.online && data.onlineSince != null) {
                recordSession(data.onlineSince, now);
                data.onlineSince = now; // avoid double-counting
            }
        }
    }

    private void cleanOldSessions() {
        long cutoff = Instant.now().getEpochSecond() - (playtimeWindowMillis / 1000);
        synchronized (recentSessions) {
            recentSessions.removeIf(seg -> seg[1] <= cutoff);
        }
    }

    /**
     * Total seconds of playtime that fall inside [now - playtimeWindow, now].
     * Includes completed sessions (clipped to the window) and current online sessions.
     */
    private long computePlaytimeSeconds(Instant now) {
        long nowSec = now.getEpochSecond();
        long windowStart = nowSec - (playtimeWindowMillis / 1000);
        if (windowStart < 0) windowStart = 0;

        long total = 0;
        synchronized (recentSessions) {
            for (long[] seg : recentSessions) {
                long start = Math.max(seg[0], windowStart);
                long end = Math.min(seg[1], nowSec);
                if (end > start) {
                    total += (end - start);
                }
            }
        }

        // Add live online sessions (not yet recorded)
        for (PlayerData data : playerData.values()) {
            if (data.online && data.onlineSince != null) {
                long start = Math.max(data.onlineSince.getEpochSecond(), windowStart);
                if (nowSec > start) {
                    total += (nowSec - start);
                }
            }
        }
        return total;
    }

    private String formatTotalHours(long totalSeconds) {
        double hours = totalSeconds / 3600.0;
        if (hours < 0.05) {
            return "0";
        }
        // One decimal place, strip trailing .0
        String s = String.format(Locale.US, "%.1f", hours);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    private void loadPlaytime() {
        Path file = dataDirectory.resolve("playtime.yml");
        if (!Files.exists(file)) {
            return;
        }
        try {
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(file)) {
                Object raw = yaml.load(in);
                if (!(raw instanceof List<?> list)) {
                    return;
                }
                long cutoff = Instant.now().getEpochSecond() - (playtimeWindowMillis / 1000);
                int loaded = 0;
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) continue;
                    Object s = map.get("start");
                    Object e = map.get("end");
                    if (!(s instanceof Number) || !(e instanceof Number)) continue;
                    long start = ((Number) s).longValue();
                    long end = ((Number) e).longValue();
                    if (end <= start || end <= cutoff) continue;
                    recentSessions.add(new long[]{start, end});
                    loaded++;
                }
                logger.info("Loaded {} playtime sessions", loaded);
            }
        } catch (Exception e) {
            logger.warn("Could not load playtime data", e);
        }
    }

    private void savePlaytime() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
            cleanOldSessions();
            List<Map<String, Long>> list = new ArrayList<>();
            synchronized (recentSessions) {
                for (long[] seg : recentSessions) {
                    Map<String, Long> m = new LinkedHashMap<>();
                    m.put("start", seg[0]);
                    m.put("end", seg[1]);
                    list.add(m);
                }
            }
            Path file = dataDirectory.resolve("playtime.yml");
            Yaml yaml = new Yaml();
            Files.writeString(file, yaml.dump(list));
        } catch (IOException e) {
            logger.error("Failed to save playtime data", e);
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
