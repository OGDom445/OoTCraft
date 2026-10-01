# OoTCraft

**Play The Legend of Zelda: Ocarina of Time as Steve.**

OoTCraft runs Minecraft Java Edition 1.21 and [Ship of Harkinian](https://github.com/HarbourMasters/Shipwright) (the Ocarina of Time PC port) side by side, connected by a shared-memory bridge, the way the SkyCraft mod connects Minecraft and Skyrim.

- **Minecraft is the player.** It handles movement, jumping, swimming, the camera, inventory, crafting, health, hunger and blocks. Its window is hidden; its hand and HUD are drawn over Zelda's picture.
- **Zelda is the world.** Hyrule, its enemies, bosses, NPCs, dialogue, cutscenes, doors, chests and story all run in the real game, untouched.
- **Hyrule can be dug into.** Mine the ground and that block of the level turns into a real Minecraft block. Under the surface there is a Minecraft world: dirt, stone, deepslate, ores, caves and bedrock. Hyrule itself stays the surface.

> OoTCraft does not include any Nintendo or Mojang assets. You need your own Ocarina of Time ROM and the Minecraft game files. See **Requirements**.

## Features

| | |
|---|---|
| **Steve in Hyrule** | Link is drawn as Steve with your Minecraft skin. First person uses Minecraft's camera, hand and HUD. |
| **Blocks** | Place any Minecraft block in Hyrule. Zelda draws it with Minecraft's real textures (any resource pack) and gives it real collision, so Link can stand on it, hookshot to wood, and so on. |
| **Breakable Hyrule** | Mining Zelda's ground cuts out exactly that block of the level and replaces it with the matching Minecraft block (grass, dirt, sand, stone, planks…). Dig down through dirt and stone into deepslate, ores (coal, iron, copper, gold, redstone, lapis, diamond), gravel, granite and diorite, with caves along the way and bedrock at the bottom. Mined blocks drop as items. Digs are saved per save file. Dungeons and boss rooms can't be dug, so their puzzles still work. |
| **TNT** | Explosions blow craters into Hyrule's ground, lined with Minecraft blocks. Zelda's own bombs do too. |
| **Combat** | Minecraft weapons hit Zelda enemies as Zelda attacks (sword, arrows, fire, explosions), so enemies with special weak points still work. Zelda damage hurts Steve. |
| **Zelda items** | Every item Link owns appears in Steve's inventory. The hookshot and longshot are real grapples in Minecraft. Bombs throw real Zelda bombs. Everything else (ocarina, slingshot, boomerang, magic, bottles, hammer, lens, masks, trade items) hands control to Link to use it. |
| **Talking and story** | Dialogue, cutscenes, the pause menu and the ocarina pass control to Zelda automatically, then back to Minecraft. |
| **Third person** | F5 switches to Zelda's own third-person camera with Link's full controls; F5 again returns to Steve. |
| **Climbing** | Zelda's ladders and vines climb like Minecraft ladders. |
| **Blocking** | NPCs, signs and other Zelda objects block Steve the way they block Link. |
| **Safety** | Steve can't fall out of the world: below bedrock he's put back, and if that spot is gone too, Zelda's own void-out respawns him at the entrance. |

## Controls

| Key | Action |
|---|---|
| WASD, mouse, Space, Shift, Ctrl | Minecraft movement, look, jump, sneak, sprint |
| Left click | Attack / mine (Hyrule's ground included) |
| Right click | Place / use. On a "Speak / Open / Check" prompt, interact with Zelda |
| R | Zelda's A button (talk, open doors and chests, read signs) |
| 1–9, mouse wheel | Hotbar |
| E | Minecraft inventory |
| Enter | Zelda pause menu (items, map, equipment, save) |
| F5 | Zelda third person as Link (F5 again to go back) |
| Esc / F1 | Ship of Harkinian menu |

**In dialogue, menus and cutscenes:** Space or left click = A, right click or Backspace = B, Enter = Start, Q = Z, Shift = R.

**As Link (F5):** Ship of Harkinian's keyboard controls apply, the mouse orbits the camera, 1/2/3 = C-left/down/right, F = C-up.

## Requirements

- Windows 10/11 x64. The bridge uses Windows shared memory.
- An Ocarina of Time ROM you own. Ship of Harkinian asks for it on first launch, and supports the same ROMs Ship of Harkinian does.
- Minecraft Java Edition. OoTCraft runs Minecraft 1.21 through Fabric's development launcher; please own the game.
- [Git](https://git-scm.com/), [CMake](https://cmake.org/) 3.26+, [Python 3](https://www.python.org/), and Visual Studio 2022 or its Build Tools with the "Desktop development with C++" workload.
- A JDK 21 or newer, for example [Eclipse Temurin](https://adoptium.net/).

## Setup

```powershell
git clone https://github.com/<you>/OoTCraft.git
cd OoTCraft
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
```

`setup.ps1` does the following:

1. Clones Ship of Harkinian 9.2.3 next to the mod (`OoTCraft\Shipwright`).
2. Applies `soh\ootcraft-soh-9.2.3.patch`.
3. Builds Ship of Harkinian and its asset archive.
4. Pre-builds the Minecraft mod.

When it finishes, run `Shipwright\x64\Release\soh.exe`. The first time, choose your ROM so Ship of Harkinian can extract the game's assets. Load a save file and Minecraft starts automatically in the background; the first start downloads Minecraft and takes a few minutes. Turn the mod on or off in **Enhancements → Minecraft Mode**.

Ship of Harkinian finds the `ootmc` folder automatically when it's next to `soh.exe` or up to three folders above it, and finds Java through `JAVA_HOME` or the usual install folders. To point elsewhere, set `gEnhancements.Minecraft.ModDir` / `gEnhancements.Minecraft.JavaHome` in `shipofharkinian.json`.

## How it works

```
 Ship of Harkinian (visible window)                 Minecraft 1.21 + ootmc (hidden window)
 ───────────────────────────────────                ──────────────────────────────────────
 Hyrule, actors, cutscenes, story        ◄──────►   player physics, camera, inventory, blocks
 draws the world + Minecraft's HUD layer  shared    collision copy of Hyrule (1/8-block voxels)
 Link = puppet at Steve's position        memory    invisible stand-ins for Zelda enemies
 block chunks drawn with MC textures      %TEMP%    block palette + textures exported for Zelda
```

- **`%TEMP%\oot_mc_bridge.bin`** holds the protocol: player, camera and input state, event rings both ways, block sections, moving platforms and nearby actors.
- **`oot_mc_mesh_<scene>.bin`** is Hyrule's collision, rebuilt whenever ground is dug.
- **`oot_mc_frame.bin`** carries Minecraft's hand and HUD, read back from the GPU and drawn by Zelda.
- **`oot_mc_blocktex.bin`** holds the real textures, shapes and collision of every block in use.
- **`oot_mc_skin.bin`** is your skin.

The Zelda side lives in `soh/soh/Enhancements/Minecraft/` (after the patch is applied); the Minecraft side is the `ootmc` Fabric mod.

## Known limitations

- Windows only.
- Items that need aiming or careful timing (slingshot, boomerang, hookshot targets in dungeons, ocarina songs) are used as Link in third person, not in Minecraft's first person.
- Hyrule's ground is cut a block at a time, but pre-rendered rooms (houses, shops, Castle Town Market) are painted backgrounds and can't be dug.
- Minecraft mobs, dropped items and particles aren't drawn in Zelda's view; drops fly straight into your inventory.
- Inside dungeons and boss rooms, digging is turned off on purpose.

## Credits and license

- OoTCraft code (the `ootmc` mod, the patch's new files and the scripts): MIT, see [LICENSE](LICENSE).
- [Ship of Harkinian](https://github.com/HarbourMasters/Shipwright) by HarbourMasters, built on the [zeldaret/oot](https://github.com/zeldaret/oot) decompilation. The patch modifies a few of its files; those changes follow Ship of Harkinian's terms.
- Minecraft is © Mojang Studios / Microsoft. The Legend of Zelda: Ocarina of Time is © Nintendo. This is an unofficial fan project, not affiliated with or endorsed by either; no game assets are included or distributed.
- Inspired by SkyCraft (Minecraft × Skyrim).
