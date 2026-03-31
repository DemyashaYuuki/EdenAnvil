# EdenAnvilSpin

Purpur/Paper 1.21.10 plugin: when a player opens a **real placed anvil**, the GUI is immediately closed, the **real anvil block is removed from the world for the duration of the music**, a floating spinning `BlockDisplay` anvil appears slightly above the block position, and the custom sound from the supplied resource pack starts playing.

## What it does now

- detects opening of a **real placed anvil**
- immediately closes the anvil GUI for the player
- removes the **actual anvil block** from the world for the configured duration
- spawns a floating spinning **BlockDisplay** using the original anvil block data
- plays the custom sound once when the animation starts
- restores the original anvil block automatically when the timer ends
- blocks breaking/placing into that exact block position while the animation is active

## Default timing

The default duration is set to **143.0 seconds** (`2860` ticks) in `config.yml`.

## Build locally

```bash
mvn clean package
```

The jar will be in `target/`.

## GitHub build

Upload this project to a GitHub repository and run the included Actions workflow. The built jar will be attached as an artifact.

## Install

1. Put the compiled plugin jar into `plugins/`.
2. Put `EdenAnvilPack-v2.zip` into the client's `resourcepacks/` folder, or host it and set it as the server resource pack.
3. Restart the server.

## Notes

- This works only for **placed anvils**. Virtual anvils from other plugins are ignored.
- The sound key is configurable in `config.yml`.
- If another plugin or command forcibly places a block into the same spot during the animation, the plugin will avoid overwriting that block when restoring.
