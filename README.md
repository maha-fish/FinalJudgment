# Final Judgment

**[English](README.md) | [简体中文](README.zh-CN.md)**

A creative-mode-only artifact weapon mod for Minecraft 1.20.1 Forge.

## Features

- Creative-mode-only artifact weapon.
- No crafting recipe; obtainable only from the creative inventory.
- Layered attack paths that bypass most conventional defenses.
- Pickup-bound: cannot be dropped or disarmed.
- Optional diagnostic logging.

## Weapon Operation

| Input | Action |
|---|---|
| Left click | Melee attack on the nearest valid entity within reach. |
| Right click | Ranged area attack; scans nearby entities and triggers the attack chain. |
| Shift + Right click | Long-range attack on a valid entity up to 64 blocks away. |

Each input has an independent 50 ms cooldown. Attacks execute the layered kill chain automatically.

## Requirements

- Minecraft 1.20.1
- Forge 47.x

## Installation

1. Download the latest release package (`.zip`).
2. Extract the archive.
3. Place all `.jar` files from the extracted folder into the `mods/` directory.
4. Launch the game with Forge 1.20.1.

The package contains all required components; no additional downloads are needed.

## Notes

- The weapon is creative-mode only. Restrict creative mode access on public servers.

## External System Interactions

At startup, Final Judgment extracts bundled runtime components (Java Agent, native library, bootstrap jar) from the mod jar to the system temporary directory, then loads or injects them. Temporary files are removed when the game exits. No user data is collected, stored, or transmitted.

## Technical Notes

- Uses Forge Coremod, Java Agent, Mixin, and optional native support.
- All injection points are visible in the startup log.
- Designed to fail safely: if a layer is unavailable, the remaining layers continue to operate.

## License

All rights reserved. Redistribution of modified builds is not permitted without explicit permission.
