# RankHomes 1.1.0

Rank-based homes for Paper, driven by LuckPerms groups, stored in SQLite.

## Jar kaise banayein (sabse aasan: GitHub Actions, kuch install nahi karna)

1. GitHub par ek naya repository banao aur is poore folder ko upload/push karo.
2. **Actions** tab -> **Build RankHomes** -> **Run workflow** (push par apne aap bhi chalta hai).
3. Khatam hone par run page ke **Artifacts** mein `RankHomes-jar` download karo; usme `RankHomes-1.1.0.jar` hai.
4. Jar ko server ke `plugins/` folder mein daalo.

Agar build fail ho to error ka text bhej do, main fix kar dunga.

## Build locally

Requires JDK 25 and Gradle (8.14+ with the Java 25 toolchain; use Gradle 9.1+ if Gradle itself runs on Java 25).

```bash
gradle build            # -> build/libs/RankHomes-1.1.0.jar
```

The Paper API coordinate lives in `gradle.properties` (`paperVersion`). Set it to the
version matching your server (see https://docs.papermc.io/paper/dev/project-setup).
`api-version` in `plugin.yml` is `26.1`; change it if your server requires otherwise.

## Install

1. Put the jar in `plugins/` (LuckPerms optional but recommended).
2. Start once, edit `plugins/RankHomes/config.yml`, run `/homes reload`.

## Ranks (100% config.yml, no rebuild needed)

Ranks live only in the `ranks:` section. Nothing about rank names or numbers is in the Java code.

- **Add** a rank: copy a block and edit it. **Remove**: delete the block or set `enabled: false`.
- **Rename**: change `display-name` (shown in the GUI). The key is just a label.
- **Reorder**: move the blocks. Order matters with `homes.rank-mode: ORDER`.
- **Custom LuckPerms group names**: `group:` is the exact LuckPerms group (`/lp listgroups`), e.g. `donator_gold`.
- Optional `permission:` also grants a rank without a group.
- `/homes reload` applies changes; `/homes ranks` shows what was loaded and flags groups LuckPerms doesn't know.

```yaml
ranks:
  supporter:
    group: donator_gold
    limit: 5
    display-name: "&eSupporter"
```

How a limit is decided (capped by `homes.maximum-limit`):
1. `/homes setlimit` override, if set.
2. Matching ranks: `HIGHEST` mode = best limit wins (default-limit is the floor); `ORDER` mode = first match in file order wins.
3. `rankhomes.homes.<n>` permission nodes.
4. `homes.default-limit` otherwise.

Without the LuckPerms plugin, groups are matched through `group.<name>` permissions.
A demoted player keeps homes above the new limit but cannot add more.

## Commands

| Command | Notes |
|---------|-------|
| `/sethome [name]` | Creates or moves a home (default name `home`). |
| `/home [name]` | No name: `home`, else the only home, else opens the menu. |
| `/delhome [name]` | Deletes a home. |
| `/homes` | Opens the menu (`/homes list` for chat). |
| `/homes reload` | Admin. Reloads config, ranks and command aliases. |
| `/homes ranks` | Admin. Shows the ranks loaded from config. |
| `/homes setlimit <player> <n\|reset>` | Admin. Works for offline players seen before. |

Aliases are set under `commands:` in `config.yml`. If another plugin (e.g. EssentialsX) already owns
`/home`, remove it or use `/rankhomes:home`.

## Permissions

`rankhomes.use` (default true), `rankhomes.admin` (op), `rankhomes.bypass.delay`, `rankhomes.bypass.cooldown`,
`rankhomes.bypass.safety`, `rankhomes.bypass.blockedworlds`, `rankhomes.homes.<n>`.

## Storage

`plugins/RankHomes/homes.db` (SQLite, WAL mode). Stores world name + UID, x/y/z, yaw, pitch.
All database work runs on one background thread; homes are cached per online player.
Homes in deleted/unloaded worlds are kept, shown with a barrier icon, and teleporting to them gives a
message instead of an error. A `homes.yml` from RankHomes 1.0.0 is imported automatically.

## Teleport safety

Before teleporting, the destination chunk is loaded asynchronously and checked using real collision
shapes: inside the world border, no overlap with solid blocks, no lava/fire/magma/cactus etc.
(`teleport.safety.unsafe-blocks`), and solid ground or water below. In `SEARCH` mode the nearest safe spot
within the configured radius is used; in `DENY` mode the teleport is refused.
