# OoTCraft setup: fetch Ship of Harkinian 9.2.3, apply the OoTCraft patch, build it, and pre-build the Minecraft mod.
# Run from the OoTCraft folder:  powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
param([switch]$SkipMod)  # the installer gets the mod separately
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$soh = Join-Path $root "Shipwright"
$patch = Join-Path $root "soh\ootcraft-soh-9.2.3.patch"
$sohCommit = "cb71e22"   # Ship of Harkinian 9.2.3 "Ackbar Delta"

function Need($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "$cmd not found: $hint" }
}
Need git "install Git from https://git-scm.com/"
Need cmake "install CMake 3.26+ from https://cmake.org/"
Need python "install Python 3 from https://www.python.org/"

# 1. Ship of Harkinian
if (-not (Test-Path $soh)) {
    Write-Host "Cloning Ship of Harkinian..."
    git clone https://github.com/HarbourMasters/Shipwright.git $soh
}
Push-Location $soh
try {
    git checkout $sohCommit
    git submodule update --init --recursive

    # 2. The OoTCraft patch (skipped if it's already applied)
    git apply --check $patch 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Applying the OoTCraft patch..."
        git apply --whitespace=nowarn $patch
    } else {
        git apply --reverse --check $patch 2>$null
        if ($LASTEXITCODE -ne 0) { throw "The OoTCraft patch doesn't apply to this Ship of Harkinian checkout" }
        Write-Host "OoTCraft patch already applied."
    }

    # 3. Build Ship of Harkinian and its own asset archive (soh.o2r)
    Write-Host "Building Ship of Harkinian (this takes a while)..."
    cmake -S . -B build/x64 -G "Visual Studio 17 2022" -T v143 -A x64
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed (is Visual Studio 2022 with C++ installed?)" }
    cmake --build build/x64 --target GenerateSohOtr --config Release
    if ($LASTEXITCODE -ne 0) { throw "Building soh.o2r failed" }
    cmake --build build/x64 --config Release --target soh -- /m
    if ($LASTEXITCODE -ne 0) { throw "Building Ship of Harkinian failed" }
} finally {
    Pop-Location
}

if ($SkipMod) { return }

# 4. The Minecraft mod (Loom fetches the Minecraft 1.21 libraries it compiles against)
Write-Host "Building the ootmc Minecraft mod..."
Push-Location (Join-Path $root "ootmc")
try {
    .\gradlew.bat build --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Building the Minecraft mod failed (needs a JDK 21+; set JAVA_HOME)" }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Done. For contributors: start Shipwright\x64\Release\soh.exe, pick your own Ocarina of Time ROM the first"
Write-Host "time, and tick Enhancements > Minecraft Mode > Developer Client to run Minecraft from this source tree."
Write-Host "Players: use Install-OoTCraft.bat instead (it sets up your Minecraft Launcher)."
