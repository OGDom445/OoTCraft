#!/usr/bin/env bash
# Builds OoTCraft-<version>-macOS.dmg (run on a Mac; GitHub Actions does this for every release).
#
# The disk image holds:
#   Install OoTCraft.app  - opens Terminal and runs the installer from a copy of OoTCraft in ~/OoTCraft
#   OoTCraft/             - the OoTCraft source of this release (code, patch, scripts; no game files)
#   README.txt, LEGAL.md, LICENSE
# Nothing copyrighted is included: the installer builds Ship of Harkinian from its official source and needs your own
# Ocarina of Time ROM and your own Minecraft.
set -euo pipefail

VERSION="${1:-dev}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${2:-$ROOT/dist}"
STAGE="$(mktemp -d)/OoTCraft"
mkdir -p "$STAGE/OoTCraft" "$OUT"

# The release's source, exactly as committed (git archive leaves out anything untracked)
git -C "$ROOT" archive --format=tar HEAD | tar -x -C "$STAGE/OoTCraft"
cp "$ROOT/LEGAL.md" "$ROOT/LICENSE" "$STAGE/"

cat > "$STAGE/README.txt" <<EOF
OoTCraft $VERSION for macOS (experimental)
Play The Legend of Zelda: Ocarina of Time as Steve. Free, and never for sale.

1. Double-click "Install OoTCraft". If macOS says it can't check the app, right-click it and choose Open
   (OoTCraft is a free fan project, so the app isn't notarized by Apple).
2. A Terminal window opens and the installer runs. It needs Homebrew (https://brew.sh) and asks for
   your password to install build tools. The first build takes 15-40 minutes.
3. Start OoTCraft with ~/Applications/OoTCraft.command. The first time, choose YOUR Ocarina of Time ROM.
4. Load a save. Your Minecraft Launcher opens: pick the OoTCraft profile and press Play.

You must own both games. OoTCraft includes no Nintendo or Mojang material. See LEGAL.md.
Help and community: https://discord.gg/w5sxzdPtT7
EOF

# The installer app: an AppleScript applet that hands off to Terminal
cat > "$STAGE/installer.applescript" <<'EOF'
set appPath to POSIX path of (path to me)
set dmgDir to do shell script "dirname " & quoted form of appPath
set src to dmgDir & "/OoTCraft/"
set cmd to "mkdir -p ~/OoTCraft && rsync -a " & quoted form of src & " ~/OoTCraft/ && cd ~/OoTCraft && chmod +x install.sh && ./install.sh"
tell application "Terminal"
    activate
    do script cmd
end tell
EOF
osacompile -o "$STAGE/Install OoTCraft.app" "$STAGE/installer.applescript"
rm "$STAGE/installer.applescript"
if [[ -f "$ROOT/docs/assets/icon.png" ]]; then
    # App icon from OoTCraft's own icon
    ICONSET="$(mktemp -d)/OoTCraft.iconset"
    mkdir -p "$ICONSET"
    for size in 16 32 128 256 512; do
        sips -z $size $size "$ROOT/docs/assets/icon.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
        sips -z $((size * 2)) $((size * 2)) "$ROOT/docs/assets/icon.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
    done
    iconutil -c icns "$ICONSET" -o "$STAGE/Install OoTCraft.app/Contents/Resources/applet.icns"
fi

DMG="$OUT/OoTCraft-$VERSION-macOS.dmg"
rm -f "$DMG"
hdiutil create -volname "OoTCraft $VERSION" -srcfolder "$STAGE" -ov -format UDZO "$DMG"
echo "Built $DMG"
