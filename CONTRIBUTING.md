# Contributing to OoTCraft

Thanks for helping! OoTCraft is a community project and **anyone can contribute**: code, testing, bug reports,
docs, ideas, art made for the project, or helping other players on the
[Discord](https://discord.gg/w5sxzdPtT7).

## Ground rules

1. **No copyrighted material.** Never add Nintendo, Mojang or Microsoft code, assets, ROMs, extracted game data,
   textures, sounds or links to download them. Your contribution must be your own work, released under the MIT
   License (see [LEGAL.md](LEGAL.md)).
2. **Be kind.** Follow the [Code of Conduct](CODE_OF_CONDUCT.md).
3. **Keep it free.** OoTCraft is non-commercial and always will be.

## Where things live

| Folder | What it is |
|---|---|
| `ootmc/` | The Minecraft mod (Fabric, Java 21, Minecraft 1.21). Player, inventory, blocks, the collision copy of Hyrule. |
| `soh/ootcraft-soh-9.2.3.patch` | Everything on the Zelda side, as a patch to Ship of Harkinian 9.2.3. New code is in `soh/soh/Enhancements/Minecraft/`. |
| `scripts/` | `install.ps1` (players), `setup.ps1` (contributors: clone, patch and build), `uninstall.ps1`. |
| `docs/assets/` | Images used by the README. |

## Setting up a development build

Requirements: Windows 10/11, Git, CMake 3.26+, Python 3, a JDK 21+, and Visual Studio 2022 (or Build Tools) with the
C++ workload. You also need your own Ocarina of Time ROM and Minecraft: Java Edition.

```powershell
git clone https://github.com/OGDom445/OoTCraft.git
cd OoTCraft
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
```

This clones Ship of Harkinian into `Shipwright/` (ignored by git), applies the patch, builds it and builds the mod.
Run `Shipwright\x64\Release\soh.exe` and tick **Enhancements → Minecraft Mode → Developer Client**, so Minecraft
starts from `ootmc/` through Gradle's development client and picks up your changes.

- Rebuild the Zelda side: `cmake --build Shipwright/build/x64 --config Release --target soh`
- Rebuild the mod: `cd ootmc` then `gradlew build`

## Sending Zelda-side changes

The Zelda side is stored as a patch. After changing files in `Shipwright/`, regenerate it:

```powershell
cd Shipwright
git add -N soh/soh/Enhancements/Minecraft
git diff cb71e22 -- soh/ > ..\soh\ootcraft-soh-9.2.3.patch
git reset -q soh/soh/Enhancements/Minecraft
```

Then commit the updated patch along with any mod changes.

## Pull requests

- Keep each pull request focused on one fix or feature. Describe what you changed and how you tested it in-game.
- Match the surrounding style: C++ uses the Ship of Harkinian clang-format style; Java uses 4-space indents.
- Comments explain *why* something is done, not what each line does.
- CI builds the mod and checks the patch still applies to a clean Ship of Harkinian 9.2.3.

## Reporting bugs

Use the **Bug report** issue form, or post in the Discord. Useful attachments:
- `Shipwright/x64/Release/logs/Ship of Harkinian.log` (the Zelda side, including crash tracebacks)
- `%APPDATA%\.ootcraft\logs\latest.log` (the Minecraft side)
- What you were doing, and in which area of the game.

Please never attach ROMs or game files.
