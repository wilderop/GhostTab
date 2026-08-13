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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
        version = "1.8",
        description = "Shows online players with session time and recently offline players in the tab list",
        authors = {"wilderop"}
)
public class GhostTabPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private final Map<UUID, PlayerData> playerData = new ConcurrentHashMap<>();
    private final List<long[]> recentSessions = Collections.synchronizedList(new ArrayList<>());
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

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

        server.getScheduler()
                .buildTask(this, this::updateAllTabLists)
                .repeat(updateIntervalSeconds, TimeUnit.SECONDS)
                .schedule();

        server.getScheduler()
                .buildTask(this, this::saveData)
                .repeat(5, TimeUnit.MINUTES)
                .schedule();

        logger.info("GhostTab v1.8 enabled. Offline window: {}h, playtime window: {}h, update every {}s",
                TimeUnit.MILLISECONDS.toHours(offlineWindowMillis),
                TimeUnit.MILLISECONDS.toHours(playtimeWindowMillis),
                updateIntervalSeconds);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
        Instant joined = Instant.now();
        data.onlineSince = joined;
        data.playtimeSegmentStart = joined;
        data.lastSeen = joined;
        data.online = true;
        data.nickDisplay = null;
        data.ghostTabApplied = false;
        captureSkin(data, player);

        for (long delayMs : new long[]{300L, 1000L, 2500L}) {
            server.getScheduler().buildTask(this, () -> {
                captureNickFromTab(player, data);
                updateAllTabLists();
            }).delay(delayMs, TimeUnit.MILLISECONDS).schedule();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerData data = playerData.get(uuid);
        if (data != null) {
            Instant now = Instant.now();
            captureSkin(data, player);
            Instant segStart = data.playtimeSegmentStart != null
                    ? data.playtimeSegmentStart
                    : data.onlineSince;
            if (data.online && segStart != null) {
                recordSession(segStart, now);
            }
            data.online = false;
            data.lastSeen = now;
            data.onlineSince = null;
            data.playtimeSegmentStart = null;
        }

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

        online.sort(Comparator.comparing(
                (PlayerData d) -> d.onlineSince != null ? d.onlineSince : Instant.MAX));
        offline.sort(Comparator.comparing(
                (PlayerData d) -> d.lastSeen != null ? d.lastSeen : Instant.MIN).reversed());

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

        Set<UUID> onlineUuids = server.getAllPlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());

        Set<UUID> desiredGhosts = offline.stream()
                .map(d -> d.uuid)
                .filter(uuid -> uuid != null && !onlineUuids.contains(uuid))
                .collect(Collectors.toSet());

        for (TabListEntry existing : new ArrayList<>(tabList.getEntries())) {
            UUID id = existing.getProfile().getId();
            if (!onlineUuids.contains(id) && !desiredGhosts.contains(id)) {
                tabList.removeEntry(id);
            }
        }

        int onlineOrder = 10000 + online.size();
        for (PlayerData data : online) {
            onlineOrder--;
            if (data.uuid == null) continue;

            Optional<TabListEntry> existingOpt = tabList.getEntry(data.uuid);
            if (existingOpt.isEmpty()) {
                continue;
            }

            TabListEntry existing = existingOpt.get();

            if (!data.ghostTabApplied) {
                captureNickFromEntry(data, existing);
            }

            Instant since = data.onlineSince != null ? data.onlineSince : now;
            String timeStr = formatDuration(Duration.between(since, now));

            Component displayComponent;
            if (data.nickDisplay != null && !looksLikeGhostTabFormat(data.nickDisplay, data.name)) {
                displayComponent = data.nickDisplay.append(parse("<gray> " + timeStr + "</gray>"));
            } else {
                String display = onlineFormat
                        .replace("{name}", data.name)
                        .replace("{time}", timeStr);
                displayComponent = parse(display);
            }

            existing.setDisplayName(displayComponent);
            data.ghostTabApplied = true;
            try {
                existing.setListOrder(onlineOrder);
            } catch (Throwable ignored) {
            }
        }

        for (PlayerData data : offline) {
            if (data.uuid == null || onlineUuids.contains(data.uuid)) continue;
            if (tabList.containsEntry(data.uuid)) {
                tabList.removeEntry(data.uuid);
            }
        }

        int ghostOrder = 1000 + offline.size();
        for (PlayerData data : offline) {
            ghostOrder--;
            if (data.uuid == null || onlineUuids.contains(data.uuid)) continue;

            String timeStr = formatDuration(Duration.between(data.lastSeen, now));
            Component displayComponent = buildOfflineDisplay(data, timeStr);

            List<GameProfile.Property> props = data.properties != null
                    ? data.properties
                    : List.of();
            GameProfile profile = new GameProfile(data.uuid, data.name, props);

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

        viewer.sendPlayerListHeaderAndFooter(header, footer);
    }

    private Component buildOfflineDisplay(PlayerData data, String timeStr) {
        if (data.nickDisplay != null && !looksLikeGhostTabFormat(data.nickDisplay, data.name)) {
            return data.nickDisplay.append(parse("<gray> offline " + timeStr + "</gray>"));
        }
        String display = offlineFormat
                .replace("{name}", data.name)
                .replace("{time}", timeStr);
        return parse(display);
    }

    private Component parse(String input) {
        try {
            return miniMessage.deserialize(input);
        } catch (Exception e) {
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

                        if (lastSeen.isBefore(cutoff)) {
                            skipped++;
                            continue;
                        }

                        PlayerData data = new PlayerData(name);
                        data.uuid = uuid;
                        data.lastSeen = lastSeen;
                        data.online = false;
                        data.properties = loadProperties(map.get("properties"));
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

                Instant effectiveLastSeen = data.online ? now : data.lastSeen;

                if (data.online || effectiveLastSeen.isAfter(cutoff)) {
                    if (data.online) {
                        server.getPlayer(data.uuid).ifPresent(p -> captureSkin(data, p));
                    }
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", data.name);
                    map.put("lastSeen", effectiveLastSeen.getEpochSecond());
                    if (data.properties != null && !data.properties.isEmpty()) {
                        map.put("properties", serializeProperties(data.properties));
                    }
                    toSave.put(entry.getKey().toString(), map);
                }
            }

            Path dataFile = dataDirectory.resolve("playerdata.yml");
            Yaml yaml = new Yaml();
            Files.writeString(dataFile, yaml.dump(toSave));
            logger.info("Saved {} player records to disk", toSave.size());

            checkpointOnlineSessions();
            savePlaytime();
        } catch (IOException e) {
            logger.error("Failed to save player data", e);
        }
    }

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

    /**
     * Flush currently-online playtime segments into the session list.
     * Does NOT reset onlineSince — that is the true session start used for tab display.
     */
    private void checkpointOnlineSessions() {
        Instant now = Instant.now();
        for (PlayerData data : playerData.values()) {
            if (data.online) {
                Instant segStart = data.playtimeSegmentStart != null
                        ? data.playtimeSegmentStart
                        : data.onlineSince;
                if (segStart != null) {
                    recordSession(segStart, now);
                    data.playtimeSegmentStart = now;
                }
            }
        }
    }

    private void cleanOldSessions() {
        long cutoff = Instant.now().getEpochSecond() - (playtimeWindowMillis / 1000);
        synchronized (recentSessions) {
            recentSessions.removeIf(seg -> seg[1] <= cutoff);
        }
    }

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

        for (PlayerData data : playerData.values()) {
            if (data.online) {
                Instant liveStart = data.playtimeSegmentStart != null
                        ? data.playtimeSegmentStart
                        : data.onlineSince;
                if (liveStart != null) {
                    long start = Math.max(liveStart.getEpochSecond(), windowStart);
                    if (nowSec > start) {
                        total += (nowSec - start);
                    }
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

    private void captureSkin(PlayerData data, Player player) {
        try {
            List<GameProfile.Property> props = player.getGameProfile().getProperties();
            if (props != null && !props.isEmpty()) {
                data.properties = new ArrayList<>(props);
            }
        } catch (Exception e) {
            logger.debug("Could not capture skin for {}", data.name, e);
        }
    }

    private void captureNickFromTab(Player player, PlayerData data) {
        try {
            player.getTabList().getEntry(player.getUniqueId()).ifPresent(entry ->
                    captureNickFromEntry(data, entry));
        } catch (Exception e) {
            logger.debug("Could not capture nick for {}", data.name, e);
        }
    }

    private void captureNickFromEntry(PlayerData data, TabListEntry entry) {
        entry.getDisplayNameComponent().ifPresent(dn -> {
            if (looksLikeGhostTabFormat(dn, data.name)) {
                return;
            }
            String plain = PlainTextComponentSerializer.plainText().serialize(dn).trim();
            if (plain.isEmpty() || plain.equalsIgnoreCase(data.name)) {
                return;
            }
            data.nickDisplay = dn;
        });
    }

    private boolean looksLikeGhostTabFormat(Component dn, String username) {
        String plain = PlainTextComponentSerializer.plainText().serialize(dn).trim();
        if (plain.isEmpty()) {
            return false;
        }
        if (plain.toLowerCase(Locale.ROOT).contains(" offline ")) {
            return true;
        }
        if (plain.matches(".*\\s+\\d+h\\s+\\d+m$")
                || plain.matches(".*\\s+\\d+m$")
                || plain.matches(".*\\s+\\d+s$")) {
            return true;
        }
        String escaped = java.util.regex.Pattern.quote(username);
        return plain.matches("(?i)" + escaped + "\\s+\\d+.*");
    }

    private List<Map<String, String>> serializeProperties(List<GameProfile.Property> props) {
        List<Map<String, String>> out = new ArrayList<>();
        for (GameProfile.Property p : props) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("value", p.getValue());
            if (p.getSignature() != null) {
                m.put("signature", p.getSignature());
            }
            out.add(m);
        }
        return out;
    }

    private List<GameProfile.Property> loadProperties(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<GameProfile.Property> props = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Object name = map.get("name");
            Object value = map.get("value");
            if (name == null || value == null) continue;
            Object sig = map.get("signature");
            String signature = sig != null ? String.valueOf(sig) : "";
            props.add(new GameProfile.Property(String.valueOf(name), String.valueOf(value), signature));
        }
        return props.isEmpty() ? null : props;
    }

    private static class PlayerData {
        UUID uuid;
        String name;
        /** True session start for tab display. Never reset while online. */
        Instant onlineSince;
        /** Rolling start for playtime checkpoints only. */
        Instant playtimeSegmentStart;
        Instant lastSeen;
        boolean online;
        List<GameProfile.Property> properties;
        Component nickDisplay;
        boolean ghostTabApplied;

        PlayerData(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
            this.lastSeen = Instant.now();
            this.online = false;
        }

        PlayerData(String name) {
            this.name = name;
            this.lastSeen = Instant.now();
            this.online = false;
        }
    }
}
