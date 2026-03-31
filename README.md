# EdenAnvilSpin

Purpur/Paper 1.21.10 plugin: when a player opens a real anvil block, the block is visually hidden for nearby players, a spinning anvil display appears, and a custom sound from the supplied resource pack starts playing.

## What it does

- detects opening of a **real placed anvil**
- leaves the actual server block intact so the anvil GUI stays open
- sends a **fake AIR block update** to nearby players so the original anvil appears gone
- spawns a **BlockDisplay** using the anvil's own block data
- rotates that display continuously while the anvil is in use
- restores the visual block and stops the custom sound when the last viewer closes the anvil

## Build locally

```bash
mvn clean package
```

The jar will be in `target/`.

## GitHub build

Upload this project to a GitHub repository and run the included Actions workflow. The built jar will be attached as an artifact.

## Install

1. Put the compiled plugin jar into `plugins/`.
2. Put `EdenAnvilPack.zip` into the client's `resourcepacks/` folder, or host it and set it as the server resource pack.
3. Restart the server.

## Notes

- This works only for **placed anvils**. Virtual anvils from other plugins are ignored.
- The real block is not destroyed server-side; it is hidden client-side so the GUI keeps working.
- The sound key is configurable in `config.yml`.
