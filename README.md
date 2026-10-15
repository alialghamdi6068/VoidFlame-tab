# VoidFlame-TAB

Custom SMP scoreboard and player-list presentation for VoidFlameMC. Target: Paper 26.2 / Java 25.

## Features
- Reads SMP kills, deaths, playtime, money and shards from VoidFlame-Core's shared SQLite database.
- Shows a compact SMP sidebar; no Practice/Duels/ELO/queue placeholders.
- Displays a colored staff star beside authorized staff names instead of rank prefixes.
- Uses LuckPerms permission nodes to control staff markers.
- Saves the player's scoreboard visibility preference in Core's `player_preferences` table.
- Does not create a local database.

## Commands
- `/vftab toggle` — toggle your SMP scoreboard.
- `/vftab reload` — reload configuration (requires `voidflame.tab.admin`).

## Dependencies
- Required: `VoidFlame-Core`
- Optional: LuckPerms for permission management.

## Permissions
- `voidflame.tab.staff` — show the staff star.
- `voidflame.staff.owner`, `voidflame.staff.manager`, `voidflame.staff.admin`, `voidflame.staff.moderator` — set star color.
- `voidflame.tab.admin` — reload the configuration.

## Build
`./gradlew build` using Java 25.
