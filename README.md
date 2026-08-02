# AscvndMaceRoulette

A Mace Roulette minigame plugin for Paper 1.21.11 (Java 21). Players queue up, get teleported to a generated void arena, and a spinning mace roulette picks a random player each round to wield a Mace and Wind Charges against the rest — last player standing wins.

**Author:** ISekai

## Features
- Queue system: players run `/mace queue` to join; a lobby countdown starts once enough players are queued.
- Auto-generated void arena with a circular stone platform, built fresh for each game.
- Animated "mace roulette" spin: a floating, spinning mace display hops between players (with a glowing highlight) before landing on a random player.
- The chosen player receives a Mace enchanted with Density 100 and 5 Wind Charges to hunt the other players.
- Round ends when a player is eliminated (killed, knocked into the void, or the macer runs out of wind charges), and a new mace roll starts automatically among survivors.
- Player inventory, location, and game mode are snapshotted before the game and fully restored afterward.
- Victory/defeat title screens and sounds for all participants.
- All player-facing messages are configurable in `config.yml`, supporting legacy `&` color codes and `&#RRGGBB` hex codes.
- Tab-completed commands.

## Commands
| Command | Permission | Description |
|---|---|---|
| `/mace queue` | everyone | Join the queue |
| `/mace leave` | everyone | Leave the queue |
| `/mace info` | everyone | Show game status |
| `/mace start` | `ascvndmaceroulette.admin` | Force-start the game |
| `/mace stop` | `ascvndmaceroulette.admin` | Stop the running game |
| `/mace reload` | `ascvndmaceroulette.admin` | Reload `config.yml` |

## Permissions
| Permission | Default | Description |
|---|---|---|
| `ascvndmaceroulette.admin` | op | Allows use of admin commands (start/stop/reload) |

## Configuration
Everything text-facing lives in `config.yml` (created automatically on first run). All messages support legacy `&` codes and `&#RRGGBB` hex codes, with placeholders such as `{player}`, `{killer}`, `{winner}`, `{seconds}`, `{count}`. You can also tune `settings:` (minimum players, countdown length, roulette length, platform radius). Run `/mace reload` to apply changes without restarting.

## Dependencies
- Paper API 1.21.11 (`compileOnly`)

## Installation
1. Download the jar from the Releases page of this repository.
2. Drop it into your server's `plugins/` folder.
3. Restart or reload the server.

## Building from source
```bash
./gradlew build
```
The compiled jar will be in `build/libs/`.

## License
See [LICENSE](LICENSE). All rights reserved — see terms above.
