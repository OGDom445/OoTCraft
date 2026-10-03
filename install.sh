#!/usr/bin/env bash
# OoTCraft installer for Linux and macOS (EXPERIMENTAL)
#
# Builds Ship of Harkinian (the Ocarina of Time PC port) from its official source with the OoTCraft patch, and adds an
# "OoTCraft" profile to YOUR OWN Minecraft Launcher. Nothing copyrighted is downloaded or shipped by OoTCraft:
#   - Ocarina of Time: Ship of Harkinian asks for your own ROM file the first time it starts.
#   - Minecraft: the official launcher downloads the game for the Microsoft account that owns it.
#
# Usage:  ./install.sh              (asks before starting)
#         ./install.sh --yes        (you've read and agree to LEGAL.md)
#         ./install.sh --build-only (just build Ship of Harkinian with OoTCraft; no Minecraft setup)
#
# Or without cloning first (it fetches OoTCraft into ~/OoTCraft, or $OOTCRAFT_DIR):
#         curl -fsSL https://raw.githubusercontent.com/OGDom445/OoTCraft/main/install.sh | bash
#
# Linux and macOS support is new and less tested than Windows. Please report problems:
# https://github.com/OGDom445/OoTCraft/issues  ·  https://discord.gg/w5sxzdPtT7

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-.}")" 2>/dev/null && pwd || pwd)"
REPO="OGDom445/OoTCraft"
SOH_COMMIT="cb71e22"               # Ship of Harkinian 9.2.3 "Ackbar Delta"
MC_VERSION="1.21"
PATCH="$ROOT/soh/ootcraft-soh-9.2.3.patch"
SOH="$ROOT/Shipwright"
YES=0
BUILD_ONLY=0   # contributors / testing: install dependencies and build Ship of Harkinian, nothing else
for arg in "$@"; do
    case "$arg" in
        --yes) YES=1 ;;
        --build-only) BUILD_ONLY=1 ;;
        *) echo "unknown option: $arg (use --yes, --build-only)"; exit 1 ;;
    esac
done

title() { printf '\n\033[36m== %s\033[0m\n' "$1"; }
ok()    { printf '   \033[32m%s\033[0m\n' "$1"; }
info()  { printf '   %s\n' "$1"; }
fail()  { printf '\n\033[31mOoTCraft install stopped: %s\033[0m\n' "$1"; exit 1; }
# Questions come from the keyboard even when the script itself arrives through a pipe (curl ... | bash)
ask()   { local reply=""; if [[ -r /dev/tty ]]; then read -r -p "$1" reply < /dev/tty || true; fi; printf '%s' "$reply"; }

# ---- Not inside an OoTCraft checkout (run from the web or copied on its own): fetch OoTCraft, then run that copy
if [[ ! -f "$ROOT/soh/ootcraft-soh-9.2.3.patch" ]]; then
    DEST="${OOTCRAFT_DIR:-$HOME/OoTCraft}"
    command -v git >/dev/null 2>&1 || fail "git is needed: install it with your package manager (e.g. sudo apt install git), then run again."
    PARENT="$(dirname "$DEST")"
    if ! mkdir -p "$PARENT" 2>/dev/null || [[ ! -w "$PARENT" ]]; then
        fail "can't write to $PARENT. If that's your home folder, it belongs to another user (a common WSL problem); fix it with: sudo chown -R \"\$USER\": \"\$HOME\" (or choose another folder: OOTCRAFT_DIR=/path/to/OoTCraft)"
    fi
    if [[ -d "$DEST/.git" ]]; then
        printf 'Updating OoTCraft in %s\n' "$DEST"
        git -C "$DEST" pull --ff-only -q || fail "couldn't update $DEST (local changes?)."
    else
        printf 'Downloading OoTCraft into %s\n' "$DEST"
        git clone -q --branch "${OOTCRAFT_REF:-main}" "https://github.com/$REPO.git" "$DEST" || fail "couldn't download OoTCraft from GitHub."
    fi
    exec bash "$DEST/install.sh" "$@"
fi
[[ -w "$ROOT" ]] || fail "can't write to $ROOT (the build goes next to this script). Clone OoTCraft into a folder you own, e.g. your home folder."

printf '\n  \033[32mOoTCraft installer\033[0m (Linux / macOS, experimental)\n'
printf '  Play The Legend of Zelda: Ocarina of Time as Steve. Free, and never for sale.\n'
printf '  Community: https://discord.gg/w5sxzdPtT7\n'

# ---- 0. The rules ----------------------------------------------------------------------------------------------------
title "Before you start"
info "OoTCraft is a free, unofficial fan project. It contains no Nintendo or Mojang code or assets."
info "To play you must OWN both games:"
info "  - The Legend of Zelda: Ocarina of Time, as a ROM you dumped yourself from your own cartridge."
info "  - Minecraft: Java Edition, on the Microsoft account you sign in to the Minecraft Launcher with."
info "Full terms: LEGAL.md. Not affiliated with or endorsed by Nintendo, Mojang, Microsoft or HarbourMasters."
if [[ $YES -eq 0 ]]; then
    answer="$(ask "   Do you own both games and agree to LEGAL.md? (yes/no) ")"
    [[ "$answer" =~ ^([yY]|[yY][eE][sS])$ ]] || fail "you need to own both games to use OoTCraft (answer yes, or run with --yes)."
fi

# ---- 1. Platform and the Minecraft Launcher --------------------------------------------------------------------------
OS="$(uname -s)"
if [[ "$OS" == Linux ]] && grep -qi microsoft /proc/version 2>/dev/null; then
    WSL=1
    info "Note: this is WSL (Linux inside Windows). To play on this PC, use Install-OoTCraft.bat in Windows instead;"
    info "WSL is only useful here for building (--build-only)."
fi
title "Checking for the Minecraft Launcher"
case "$OS" in
    Linux)
        MC_DIR="$HOME/.minecraft"
        if [[ -d "$HOME/.var/app/com.mojang.Minecraft/.minecraft" && ! -d "$MC_DIR" ]]; then
            MC_DIR="$HOME/.var/app/com.mojang.Minecraft/.minecraft"   # Flatpak launcher
        fi
        PROFILE_DIR="$HOME/.ootcraft"
        command -v minecraft-launcher >/dev/null 2>&1 || command -v flatpak >/dev/null 2>&1 \
            || [[ -d "$MC_DIR" ]] || LAUNCHER_MISSING=1
        ;;
    Darwin)
        MC_DIR="$HOME/Library/Application Support/minecraft"
        PROFILE_DIR="$HOME/Library/Application Support/ootcraft"
        [[ -d "/Applications/Minecraft.app" || -d "$HOME/Applications/Minecraft.app" ]] || LAUNCHER_MISSING=1
        ;;
    *) fail "unsupported system: $OS (OoTCraft supports Windows, Linux and macOS)." ;;
esac
if [[ $BUILD_ONLY -eq 0 ]] && [[ "${LAUNCHER_MISSING:-0}" == 1 || ! -d "$MC_DIR" ]]; then
    [[ "${WSL:-0}" == 1 ]] && fail "no Minecraft Launcher in WSL. On Windows, run Install-OoTCraft.bat instead (or use --build-only to just build here)."
    fail "install the official Minecraft Launcher (https://www.minecraft.net/download), sign in with the account that owns Minecraft: Java Edition, start Minecraft once, then run this installer again."
fi
ok "Minecraft folder: $MC_DIR"

# ---- 2. Build tools ----------------------------------------------------------------------------------------------------
title "Installing build tools (needs your password for the package manager)"
if [[ "$OS" == Linux ]]; then
    if command -v apt-get >/dev/null; then
        sudo apt-get update
        sudo apt-get install -y gcc g++ git cmake ninja-build lsb-release libsdl2-dev libpng-dev libsdl2-net-dev \
            libzip-dev zipcmp zipmerge ziptool nlohmann-json3-dev libtinyxml2-dev libspdlog-dev libopengl-dev \
            libopusfile-dev libvorbis-dev python3 curl
        sudo apt-get install -y openjdk-25-jdk-headless || sudo apt-get install -y openjdk-21-jdk-headless
    elif command -v dnf >/dev/null; then
        sudo dnf install -y gcc gcc-c++ git cmake ninja-build lsb_release SDL2-devel libpng-devel libzip-devel \
            libzip-tools nlohmann-json-devel tinyxml2-devel spdlog-devel opusfile-devel libvorbis-devel python3 curl
        sudo dnf install -y java-25-openjdk-devel || sudo dnf install -y java-21-openjdk-devel
    elif command -v pacman >/dev/null; then
        sudo pacman -S --needed --noconfirm gcc git cmake ninja lsb-release sdl2 libpng libzip nlohmann-json \
            tinyxml2 spdlog sdl2_net opusfile libvorbis python curl jdk-openjdk
    else
        fail "unknown package manager. Install Ship of Harkinian's Linux build dependencies (see its docs/BUILDING.md), python3, curl and a JDK, then run again."
    fi
else
    command -v brew >/dev/null || fail "Homebrew is needed: install it from https://brew.sh/ and run again."
    xcode-select -p >/dev/null 2>&1 || { xcode-select --install || true; fail "install the Xcode command line tools (a window opened), then run again."; }
    brew install sdl2 libpng glew ninja cmake tinyxml2 nlohmann-json libzip opusfile libvorbis python git openjdk
    export PATH="$(brew --prefix openjdk)/bin:$PATH"
fi
command -v java >/dev/null || fail "Java wasn't found after installing it. Open a new terminal and run again."
ok "Build tools ready"

# ---- 3. Ship of Harkinian + the OoTCraft patch, built on this computer ----------------------------------------------------
title "Building Ship of Harkinian with OoTCraft (first time: 15-40 minutes)"
if [[ ! -d "$SOH/.git" ]]; then
    git clone https://github.com/HarbourMasters/Shipwright.git "$SOH"
fi
cd "$SOH"
git checkout -q "$SOH_COMMIT"
git submodule update --init --recursive
if git apply --check --whitespace=nowarn "$PATCH" 2>/dev/null; then
    git apply --whitespace=nowarn "$PATCH"
    ok "OoTCraft patch applied"
elif git apply --reverse --check --whitespace=nowarn "$PATCH" 2>/dev/null; then
    ok "OoTCraft patch already applied"
else
    fail "the OoTCraft patch doesn't apply to this Ship of Harkinian checkout."
fi
cmake -H. -Bbuild-cmake -GNinja -DCMAKE_BUILD_TYPE:STRING=Release
cmake --build build-cmake --target GenerateSohOtr
cmake --build build-cmake --config Release
if [[ "$OS" == Linux ]]; then SOH_EXE="$SOH/build-cmake/soh/soh.elf"; else SOH_EXE="$SOH/build-cmake/soh/soh-macos"; fi
[[ -x "$SOH_EXE" ]] || fail "the Ship of Harkinian build didn't produce $SOH_EXE."
ok "Built $SOH_EXE"
cd "$ROOT"
if [[ $BUILD_ONLY -eq 1 ]]; then
    title "Build finished"
    info "Run $SOH_EXE (pick your own Ocarina of Time ROM the first time)."
    exit 0
fi

# ---- 4. The OoTCraft Minecraft mod (release jar, checksum-verified) ----------------------------------------------------
title "Getting the OoTCraft Minecraft mod"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
verify() {  # file, algorithm (256/512), expected hex
    local actual
    if command -v sha${2}sum >/dev/null; then actual="$(sha${2}sum "$1" | cut -d' ' -f1)"; else actual="$(shasum -a "$2" "$1" | cut -d' ' -f1)"; fi
    local want; want="$(printf '%s' "$3" | tr '[:upper:]' '[:lower:]')"
    actual="$(printf '%s' "$actual" | tr '[:upper:]' '[:lower:]')"
    [[ -n "$want" && "$actual" == "$want" ]] || { rm -f "$1"; fail "the download $(basename "$1") failed its SHA-$2 check (corrupted or tampered). Please try again."; }
}
read -r JAR_URL JAR_NAME JAR_SHA < <(curl -fsSL -H "User-Agent: OoTCraft-Installer" "https://api.github.com/repos/$REPO/releases/latest" | python3 -c '
import json, sys
r = json.load(sys.stdin)
for a in r.get("assets", []):
    if a["name"].startswith("ootmc-") and a["name"].endswith(".jar") and "sources" not in a["name"]:
        print(a["browser_download_url"], a["name"], (a.get("digest") or "sha256:").split(":", 1)[1]); break
')
[[ -n "${JAR_URL:-}" ]] || fail "couldn't find the mod in the latest GitHub release."
curl -fsSL "$JAR_URL" -o "$TMP/$JAR_NAME"
[[ -n "$JAR_SHA" ]] && verify "$TMP/$JAR_NAME" 256 "$JAR_SHA"
ok "Downloaded $JAR_NAME (verified)"

# ---- 5. Fabric + the OoTCraft profile in YOUR Minecraft Launcher -----------------------------------------------------------
title "Adding the OoTCraft profile to your Minecraft Launcher"
LOADER="$(sed -n 's/^loader_version=//p' "$ROOT/ootmc/gradle.properties" | tr -d '\r')"
FI_VER="$(curl -fsSL https://meta.fabricmc.net/v2/versions/installer | python3 -c 'import json,sys; print(next(v["version"] for v in json.load(sys.stdin) if v["stable"]))')"
FI="$TMP/fabric-installer-$FI_VER.jar"
FI_URL="https://maven.fabricmc.net/net/fabricmc/fabric-installer/$FI_VER/fabric-installer-$FI_VER.jar"
curl -fsSL "$FI_URL" -o "$FI"
verify "$FI" 256 "$(curl -fsSL "$FI_URL.sha256" | awk '{print $1}')"
java -jar "$FI" client -dir "$MC_DIR" -mcversion "$MC_VERSION" -loader "$LOADER" -noprofile
VERSION_ID="fabric-loader-$LOADER-$MC_VERSION"
ok "Fabric $LOADER for Minecraft $MC_VERSION installed (official Fabric installer)"

mkdir -p "$PROFILE_DIR/mods"
rm -f "$PROFILE_DIR"/mods/ootmc-*.jar "$PROFILE_DIR"/mods/fabric-api-*.jar
cp "$TMP/$JAR_NAME" "$PROFILE_DIR/mods/"
read -r API_URL API_NAME API_SHA < <(curl -fsSL -H "User-Agent: OoTCraft-Installer" \
    "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22$MC_VERSION%22%5D&loaders=%5B%22fabric%22%5D" | python3 -c '
import json, sys
f = next(f for f in json.load(sys.stdin)[0]["files"] if f["primary"])
print(f["url"], f["filename"], f["hashes"]["sha512"])
')
curl -fsSL "$API_URL" -o "$PROFILE_DIR/mods/$API_NAME"
verify "$PROFILE_DIR/mods/$API_NAME" 512 "$API_SHA"
ok "Mods: $JAR_NAME, $API_NAME (verified)"

PROFILES="$MC_DIR/launcher_profiles.json"
[[ -f "$PROFILES" ]] || fail "couldn't find the launcher's profile list. Start the Minecraft Launcher once, then run again."
cp "$PROFILES" "$PROFILES.ootcraft-backup"
python3 - "$PROFILES" "$VERSION_ID" "$PROFILE_DIR" <<'PY'
import json, sys, datetime
path, version, game_dir = sys.argv[1:4]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
data.setdefault("profiles", {})["ootcraft"] = {
    "name": "OoTCraft", "type": "custom", "icon": "Grass", "created": now, "lastUsed": now,
    "lastVersionId": version, "gameDir": game_dir, "javaArgs": "-Xmx4G -XX:+UseG1GC",
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2)
PY
ok "Profile 'OoTCraft' added (Minecraft $MC_VERSION + Fabric, folder $PROFILE_DIR)"

# ---- 6. Shortcut -------------------------------------------------------------------------------------------------------------
title "Creating a shortcut"
if [[ "$OS" == Linux ]]; then
    APPS="$HOME/.local/share/applications"
    mkdir -p "$APPS"
    cat > "$APPS/ootcraft.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=OoTCraft
Comment=Ocarina of Time as Steve
Exec="$SOH_EXE"
Path=$(dirname "$SOH_EXE")
Icon=$ROOT/docs/assets/icon.png
Categories=Game;
Terminal=false
EOF
    ok "Added OoTCraft to your applications menu"
else
    mkdir -p "$HOME/Applications"
    cat > "$HOME/Applications/OoTCraft.command" <<EOF
#!/bin/bash
cd "$(dirname "$SOH_EXE")" && exec "$SOH_EXE"
EOF
    chmod +x "$HOME/Applications/OoTCraft.command"
    ok "Created ~/Applications/OoTCraft.command (double-click to play)"
fi

title "All set!"
info "1. Start OoTCraft (applications menu on Linux, ~/Applications/OoTCraft.command on macOS)."
info "2. The first time, Ship of Harkinian asks for YOUR Ocarina of Time ROM and builds its game data from it."
info "3. Load a save file. Your Minecraft Launcher opens: pick the OoTCraft profile and press Play."
info ""
info "Linux / macOS support is experimental: tell us how it went on https://discord.gg/w5sxzdPtT7"
