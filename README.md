# GhostTab

A Velocity proxy plugin that enhances the tab list with:

- **Online players** showing how long they have been online (e.g. `Steve 1h 23m`)
- **Recently offline players ("ghosts")** showing how long they have been offline (e.g. `Alex offline 2h 15m`)

Offline players remain visible for a configurable window (default **24 hours**).

## Features

- Live updating times (default every 30 seconds)
- Configurable header / footer with placeholders `{online}` and `{ghosts}`
- Configurable display formats using MiniMessage
- Persists recent player data across proxy restarts
- Online players sorted alphabetically, offline players sorted by most recently offline (configurable)
- No dependency on backend plugins or VelocityTab

## Requirements

- Velocity 3.3+
- Java 17+

## Installation

1. Download the latest `GhostTab.jar` from Releases (or build it yourself)
2. Place it in your Velocity `plugins/` folder
3. Restart / start the proxy
4. Edit `plugins/ghosttab/config.yml` as desired
5. Restart or reload if you add a reload command later

**Important:** Disable or remove VelocityTab / Velocitab if you are using this plugin, otherwise they will conflict over the tab list.

## Building

```bash
mvn clean package
```

The shaded jar will be in `target/GhostTab.jar`.

## Configuration

See `config.yml` for all options:

```yaml
offline-window-hours: 24
update-interval-seconds: 30
header: "<gold><bold>A Zombie Pigman Broke My Door</bold></gold>"
footer: "<gray>Online: {online} | Ghosts: {ghosts}</gray>"
online-format: "<white>{name} <gray>{time}</gray>"
offline-format: "<dark_gray>{name} <gray>offline {time}</gray>"
sort-offline-by-recent: true
```

## Notes

- Ghost players appear with no skin (default Steve/Alex silhouette) because the proxy does not store skin data for offline players.
- The plugin tracks players that connect through the proxy. Players who only ever joined backends directly will not appear.
- Times are approximate and update on the configured interval.

## License

MIT
