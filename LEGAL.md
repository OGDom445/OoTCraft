# Legal notice and disclaimer

**Short version:** OoTCraft is free, source-available, unofficial and non-commercial. It contains **no** copyrighted
code or assets from Nintendo, Mojang or Microsoft. To use it you must own both The Legend of Zelda: Ocarina of Time
and Minecraft: Java Edition. It comes with no warranty.

## 1. What OoTCraft is, and what it is not

- OoTCraft is a fan-made, non-commercial modding project. It is **free**. It is not sold, and nothing in it is behind
  a paywall. **Its license forbids selling it or using it commercially**: nobody may charge for OoTCraft, sell copies
  or modified versions, or put it behind a paywall.
- OoTCraft is **not affiliated with, endorsed by, sponsored by or approved by** Nintendo Co., Ltd., Mojang Studios, Microsoft Corporation, the Fabric project, or the HarbourMasters team (the authors of
  Ship of Harkinian).
- "The Legend of Zelda", "Ocarina of Time" and related names are trademarks of Nintendo. "Minecraft" is a trademark of
  Microsoft/Mojang. These names are used only to describe compatibility (what OoTCraft works with). All trademarks
  belong to their owners.

## 2. What this repository contains

Only original work by OoTCraft contributors, released under the [PolyForm Noncommercial License 1.0.0](LICENSE)
(free for any noncommercial use; selling or commercial use is not permitted). Versions up to and including v0.1.1
were published under the MIT License.

- `ootmc/`: the source code of a Minecraft: Java Edition mod (Fabric). It contains no Minecraft code or assets.
  Building it lets Fabric Loom fetch the libraries it compiles against from Mojang's and Fabric's official servers,
  as with any Fabric mod.
- `soh/ootcraft-soh-9.2.3.patch`: a patch, in standard `git diff` form, against the open-source
  [Ship of Harkinian](https://github.com/HarbourMasters/Shipwright) project. Its new files (under
  `soh/soh/Enhancements/Minecraft/`) are original OoTCraft work. The small changes to existing Ship of Harkinian
  files are shown only as diff context and are applied to the user's own checkout of the official repository.
  OoTCraft does not redistribute Ship of Harkinian.
- `scripts/`, `Install-OoTCraft.bat` and `install.sh`: installer and build scripts (Windows, Linux, macOS).
- The macOS disk image (`OoTCraft-<version>-macOS.dmg`, attached to releases): an "Install OoTCraft" app that
  runs `install.sh`, plus this repository's source, README, LEGAL.md and LICENSE. It is checked automatically to
  contain no ROMs or game data.
- Documentation.

## 3. What this repository does **not** contain, and never will

- No Nintendo ROMs, game code, game data, textures, models, music, sounds or text.
- No Minecraft game files, code, textures, sounds, or Mojang's assets.
- No prebuilt Ship of Harkinian executables.
- No links to download ROMs or cracked games. Pull requests or issues that add or request any of these will be
  closed and removed.

## 4. You must own the games

- **Ocarina of Time:** Ship of Harkinian, and therefore OoTCraft, only works with a ROM file that **you dumped
  yourself from a cartridge you own**. Ship of Harkinian asks for that file on first launch and builds its game
  data from it, on your computer only. Downloading ROMs you don't own may be illegal where you live.
- **Minecraft: Java Edition:** OoTCraft runs through **your own official Minecraft Launcher**, signed in with the
  Microsoft account that owns the game. The mod refuses to run on offline or unofficial accounts. Use of Minecraft
  is subject to the [Minecraft EULA](https://www.minecraft.net/eula) and
  [Usage Guidelines](https://www.minecraft.net/usage-guidelines).

## 5. No warranty and limitation of liability

OoTCraft is provided **"as is", without warranty of any kind**, express or implied (see "No Liability" in the
license).
The authors and contributors are not liable for any claim, damages or other liability arising from the software or
its use. This includes lost save data, corrupted worlds, crashes, hardware issues, account problems, or any
violation of third-party terms by the user. You use OoTCraft at your own risk and are responsible for complying with
the laws of your country and the terms of the games you own. Back up your saves.

## 6. Contributions

By contributing, you confirm that your contribution is your own original work, or work you have the right to
submit, and that you license it under the PolyForm Noncommercial License 1.0.0, like the rest of OoTCraft. Do not contribute code, assets or data copied from Nintendo,
Mojang, Microsoft or any other source you don't have rights to.

## 7. Takedown and contact

If you are a rights holder and believe something in this repository infringes your rights, please open an issue
titled "Rights holder request" or contact the maintainers on the
[OoTCraft Discord](https://discord.gg/w5sxzdPtT7). The maintainers will review the request promptly and remove the
material in question while it is being reviewed.

## 8. Third-party projects

OoTCraft builds on, but does not include:
- [Ship of Harkinian](https://github.com/HarbourMasters/Shipwright) by HarbourMasters, built on the
  [zeldaret/oot](https://github.com/zeldaret/oot) decompilation project.
- [Fabric](https://fabricmc.net/) (loader, API and installer), downloaded from their official servers by the
  installer.

These projects have their own licenses and terms, which apply to them.

## 9. AI-generated code

OoTCraft's source code, scripts and documentation were written **entirely with large language models** (AI coding
assistants), directed, tested and published by the project's human maintainer. The project has been play-tested, but
the code has not been reviewed line by line by a human expert and may contain bugs or security issues. This doesn't
change the license or the no-warranty terms above. Review the code yourself before relying on it, and please
report problems through issues or the [Discord](https://discord.gg/w5sxzdPtT7). Contributions from humans and AI
tools alike are welcome, under the same rules: original work only, and no copyrighted material.

*This notice is not legal advice.*
