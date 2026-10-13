# OrbitalSkybenderMod (Fabric)

A separate Fabric project under the same repository, alongside the original Paper plugin. This module is designed for Minecraft 1.21.11, Java 21, Fabric Loader 0.19.2+, and Fabric API 0.141.6+1.21.11.

## Build

With Gradle 9.7.1 installed, run `gradle build` from this directory. The JAR will be in `build/libs/OrbitalSkybenderMod-4.0.0.jar`.

A repository-level GitHub Actions workflow builds this directory and uploads the JAR as an artifact whenever the module is pushed.

## Commands

- `/skybender` — command overview
- `/skybender bend [cinematic]` — orbiting TNT display ring
- `/skybender laser` — aimable beam, 100-block range, 5 seconds
- `/skybender fire <nuke|stab|laser|wall>` — normal TNT abilities
- `/skybender target <nuke|stab> <player>` — target a player
- `/skybender awakening` — 60 seconds, then a 2-minute cooldown
- `/skybender gravity` — lift up to 8 terrain blocks; left-click a block to launch them toward your look direction
- `/skybender ultra-nuke` — same radial TNT pattern as normal nuke, with twice the radius
- `/skybender ultra-stab` — enhanced TNT barrage
- `/skybender ultra-laser` — 100-block, aimable beam for 5 seconds
- `/skybender ultra-wall` — flying wall of TNT
- `/skybender water` — toggle four orbiting axolotls
- `/skybender admin enable` — owner-only full command list
- `/skybender admin cooldownbypass` — owner-only toggle
- `/skybender admin awakeningdurationbypass` — owner-only toggle

`admin` and `awakening` are intentionally omitted from tab suggestions, but can still be typed. Admin toggles are restricted to the username `AvoidMyExplosive`.

## Note about the port

The repository's default branch currently contains only a short README and no committed Paper plugin source. This project therefore ports the described OBS behavior into a standalone Fabric module; it is not a literal source-to-source copy of uncommitted workspace files.
