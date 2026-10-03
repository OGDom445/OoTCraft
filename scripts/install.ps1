# OoTCraft installer
#
# Builds Ship of Harkinian (the Ocarina of Time PC port) from its official source with the OoTCraft patch, and adds
# an "OoTCraft" profile to YOUR OWN Minecraft Launcher. Nothing copyrighted is downloaded or shipped by OoTCraft:
#   - Ocarina of Time: Ship of Harkinian asks for your own ROM file the first time it starts.
#   - Minecraft: the official launcher downloads the game for the Microsoft account that owns it.
#
# Run it with Install-OoTCraft.bat (double-click), or:
#   powershell -ExecutionPolicy Bypass -File scripts\install.ps1

param(
    [switch]$SkipPrerequisites,   # you already have Git, CMake, Python, a JDK 25 and Visual Studio C++ build tools
    [switch]$BuildModFromSource,  # build the Minecraft mod instead of downloading the released jar
    [switch]$Yes                  # don't ask (you've read and agree to LEGAL.md)
)

# Programs like git and java print progress on stderr; Windows PowerShell 5 would treat that as a fatal error, so
# native tools are judged by their exit codes and cmdlets that must succeed use -ErrorAction Stop.
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path -Parent $PSScriptRoot
$repo = "OGDom445/OoTCraft"                   # GitHub repository (releases with the mod jar)
$minecraftVersion = "1.21"
$profileDir = Join-Path $env:APPDATA ".ootcraft"   # OoTCraft's own Minecraft folder (worlds, mods, settings)
$mcDir = Join-Path $env:APPDATA ".minecraft"

function Title($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Ok($text) { Write-Host "   $text" -ForegroundColor Green }
function Info($text) { Write-Host "   $text" }
# Every download is checked against the checksum its source publishes; a mismatch deletes it and stops the install
function Verify($file, $algorithm, $expected) {
    $actual = (Get-FileHash $file -Algorithm $algorithm).Hash
    if (-not $expected -or $actual -ne $expected.Trim().ToUpper()) {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
        Fail "the download $(Split-Path -Leaf $file) failed its $algorithm check (corrupted or tampered). Please try again."
    }
}
function Fail($text) { Write-Host ""; Write-Host "OoTCraft install stopped: $text" -ForegroundColor Red; exit 1 }

Write-Host ""
Write-Host "  OoTCraft installer" -ForegroundColor Green
Write-Host "  Play The Legend of Zelda: Ocarina of Time as Steve. Free, and never for sale." -ForegroundColor DarkGray
Write-Host "  Community: https://discord.gg/w5sxzdPtT7" -ForegroundColor DarkGray

# ---- 0. The rules -------------------------------------------------------------------------------------------------
Title "Before you start"
Info "OoTCraft is a free, unofficial fan project. It contains no Nintendo or Mojang code or assets."
Info "To play you must OWN both games:"
Info "  - The Legend of Zelda: Ocarina of Time, as a ROM you dumped yourself from your own cartridge."
Info "  - Minecraft: Java Edition, on the Microsoft account you sign in to the Minecraft Launcher with."
Info "Full terms: LEGAL.md. Not affiliated with or endorsed by Nintendo, Mojang, Microsoft or HarbourMasters."
if (-not $Yes) {
    $answer = Read-Host "   Do you own both games and agree to LEGAL.md? (yes/no)"
    if ($answer -notmatch '^(y|yes)$') { Fail "you need to own both games to use OoTCraft." }
}

# ---- 1. Minecraft Launcher ---------------------------------------------------------------------------------------
Title "Checking for the Minecraft Launcher"
$launcherFound = @(
    "${env:ProgramFiles(x86)}\Minecraft Launcher\MinecraftLauncher.exe",
    "$env:ProgramFiles\Minecraft Launcher\MinecraftLauncher.exe",
    "$env:LOCALAPPDATA\Programs\Minecraft Launcher\MinecraftLauncher.exe",
    "C:\XboxGames\Minecraft Launcher\Content\Minecraft.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $launcherFound -and (Get-AppxPackage -Name "Microsoft.4297127D64EC6" -ErrorAction SilentlyContinue)) {
    $launcherFound = "Microsoft Store"
}
if (-not $launcherFound -or -not (Test-Path $mcDir)) {
    Start-Process "https://www.minecraft.net/download"
    Fail "install the official Minecraft Launcher, sign in with the account that owns Minecraft: Java Edition, start Minecraft once, then run this installer again."
}
Ok "Found the Minecraft Launcher ($launcherFound)"

# ---- 2. Build tools ----------------------------------------------------------------------------------------------
function Have($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function RefreshPath {
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
}
function FindJdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($base in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft")) {
        if (Test-Path $base) { $candidates += Get-ChildItem $base -Directory | Sort-Object Name -Descending | ForEach-Object FullName }
    }
    foreach ($c in $candidates) {
        $release = Join-Path $c "release"
        if ((Test-Path (Join-Path $c "bin\java.exe")) -and (Test-Path $release)) {
            $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
            if ($line -and [int]$line.Matches[0].Groups[1].Value -ge 25) { return $c }
        }
    }
    return $null
}
function HasVcTools {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) { return $false }
    $found = & $vswhere -products * -version "[17.0,18.0)" -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    return [bool]$found
}

if (-not $SkipPrerequisites) {
    Title "Installing build tools (only the ones you're missing)"
    if (-not (Have winget)) { Fail "winget (App Installer) is missing. Install 'App Installer' from the Microsoft Store, or install Git, CMake, Python 3, a JDK 25 and Visual Studio 2022 Build Tools (C++) yourself and run again with -SkipPrerequisites." }
    $wingetArgs = @("--accept-source-agreements", "--accept-package-agreements", "--silent", "-e")
    if (-not (Have git)) { Info "Git..."; winget install --id Git.Git @wingetArgs | Out-Null }
    if (-not (Have cmake)) { Info "CMake..."; winget install --id Kitware.CMake @wingetArgs | Out-Null }
    if (-not (Have python)) { Info "Python 3..."; winget install --id Python.Python.3.12 @wingetArgs | Out-Null }
    if (-not (FindJdk)) { Info "Java 25 (Eclipse Temurin)..."; winget install --id EclipseAdoptium.Temurin.25.JDK @wingetArgs | Out-Null }
    if (-not (HasVcTools)) {
        Info "Visual Studio 2022 Build Tools with C++ (large download, takes a while)..."
        winget install --id Microsoft.VisualStudio.2022.BuildTools @wingetArgs --override "--wait --quiet --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended" | Out-Null
    }
    RefreshPath
}
foreach ($c in @("git", "cmake", "python")) { if (-not (Have $c)) { Fail "$c still isn't available. Open a new window and run the installer again." } }
$jdk = FindJdk
if (-not $jdk) { Fail "no Java 25+ found (it builds the mod; Minecraft itself uses its own Java). Install Eclipse Temurin 25 (https://adoptium.net/) and run again." }
$env:JAVA_HOME = $jdk
Ok "Build tools ready (Java: $jdk)"

# ---- 3. Ship of Harkinian + the OoTCraft patch, built on this PC --------------------------------------------------
Title "Building Ship of Harkinian with OoTCraft (first time: 15-40 minutes)"
& (Join-Path $PSScriptRoot "setup.ps1") -SkipMod
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { Fail "the Ship of Harkinian build failed (see the messages above)." }
$soh = Join-Path $root "Shipwright\x64\Release\soh.exe"
if (-not (Test-Path $soh)) { Fail "soh.exe wasn't built." }
Ok "Built $soh"

# ---- 4. The OoTCraft Minecraft mod -------------------------------------------------------------------------------
Title "Getting the OoTCraft Minecraft mod"
$modJar = $null
if (-not $BuildModFromSource -and $repo -notlike "OWNER/*") {
    try {
        $release = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest" -Headers @{ "User-Agent" = "OoTCraft-Installer" } -ErrorAction Stop
        $asset = $release.assets | Where-Object { $_.name -like "ootmc-*.jar" -and $_.name -notlike "*sources*" } | Select-Object -First 1
        if ($asset) {
            $modJar = Join-Path $env:TEMP $asset.name
            Invoke-WebRequest $asset.browser_download_url -OutFile $modJar -ErrorAction Stop
            if ($asset.digest -like "sha256:*") { Verify $modJar SHA256 $asset.digest.Substring(7) }
            Ok "Downloaded $($asset.name) from release $($release.tag_name)"
        }
    } catch { Info "No release download available; building the mod instead." }
}
if (-not $modJar) {
    Push-Location (Join-Path $root "ootmc")
    try {
        .\gradlew.bat build --console=plain
        if ($LASTEXITCODE -ne 0) { Fail "building the Minecraft mod failed." }
    } finally { Pop-Location }
    $modJar = Get-ChildItem (Join-Path $root "ootmc\build\libs") -Filter "ootmc-*.jar" | Where-Object { $_.Name -notlike "*sources*" } | Select-Object -First 1 -ExpandProperty FullName
    Ok "Built $(Split-Path -Leaf $modJar)"
}

# ---- 5. Fabric + the OoTCraft profile in YOUR Minecraft Launcher -----------------------------------------------------
Title "Adding the OoTCraft profile to your Minecraft Launcher"
$loaderVersion = (Select-String -Path (Join-Path $root "ootmc\gradle.properties") -Pattern '^loader_version=(.+)$').Matches[0].Groups[1].Value.Trim()
$installerMeta = Invoke-RestMethod "https://meta.fabricmc.net/v2/versions/installer" -ErrorAction Stop
$installerVersion = ($installerMeta | Where-Object stable | Select-Object -First 1).version
$fabricInstaller = Join-Path $env:TEMP "fabric-installer-$installerVersion.jar"
Invoke-WebRequest "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$installerVersion/fabric-installer-$installerVersion.jar" -OutFile $fabricInstaller -ErrorAction Stop
$fabricSha = (Invoke-WebRequest "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$installerVersion/fabric-installer-$installerVersion.jar.sha256" -UseBasicParsing -ErrorAction Stop).Content
if ($fabricSha -is [byte[]]) { $fabricSha = [Text.Encoding]::ASCII.GetString($fabricSha) }
Verify $fabricInstaller SHA256 ($fabricSha -split '\s+')[0]
& (Join-Path $jdk "bin\java.exe") -jar $fabricInstaller client -dir $mcDir -mcversion $minecraftVersion -loader $loaderVersion -noprofile
if ($LASTEXITCODE -ne 0) { Fail "the official Fabric installer failed." }
$versionId = "fabric-loader-$loaderVersion-$minecraftVersion"
Ok "Fabric $loaderVersion for Minecraft $minecraftVersion installed (official Fabric installer)"

New-Item -ItemType Directory -Force (Join-Path $profileDir "mods") | Out-Null
Get-ChildItem (Join-Path $profileDir "mods") -Filter "ootmc-*.jar" | Remove-Item -Force
Copy-Item $modJar (Join-Path $profileDir "mods") -Force -ErrorAction Stop
$fabricApi = Invoke-RestMethod ("https://api.modrinth.com/v2/project/fabric-api/version?game_versions=" + [uri]::EscapeDataString("[`"$minecraftVersion`"]") + "&loaders=" + [uri]::EscapeDataString('["fabric"]')) -Headers @{ "User-Agent" = "OoTCraft-Installer" } -ErrorAction Stop
$apiFile = ($fabricApi | Select-Object -First 1).files | Where-Object primary | Select-Object -First 1
Get-ChildItem (Join-Path $profileDir "mods") -Filter "fabric-api-*.jar" | Remove-Item -Force
Invoke-WebRequest $apiFile.url -OutFile (Join-Path $profileDir "mods\$($apiFile.filename)") -ErrorAction Stop
Verify (Join-Path $profileDir "mods\$($apiFile.filename)") SHA512 $apiFile.hashes.sha512
Ok "Mods: $(Split-Path -Leaf $modJar), $($apiFile.filename) (from Modrinth)"

$now = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
$profilesUpdated = 0
foreach ($file in @("launcher_profiles.json", "launcher_profiles_microsoft_store.json")) {
    $path = Join-Path $mcDir $file
    if (-not (Test-Path $path)) { continue }
    Copy-Item $path "$path.ootcraft-backup" -Force
    $json = Get-Content $path -Raw | ConvertFrom-Json
    if (-not $json.profiles) { $json | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{}) }
    $profile = [pscustomobject]@{
        name = "OoTCraft"; type = "custom"; icon = "Grass"; created = $now; lastUsed = $now
        lastVersionId = $versionId; gameDir = $profileDir
        javaArgs = "-Xmx4G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC"
    }
    $json.profiles | Add-Member -NotePropertyName "ootcraft" -NotePropertyValue $profile -Force
    [IO.File]::WriteAllText($path, ($json | ConvertTo-Json -Depth 32), (New-Object Text.UTF8Encoding($false)))
    $profilesUpdated++
}
if ($profilesUpdated -eq 0) { Fail "couldn't find the launcher's profile list. Start the Minecraft Launcher once, then run again." }
Ok "Profile 'OoTCraft' added (Minecraft $minecraftVersion + Fabric, folder $profileDir)"

# ---- 6. Shortcut -------------------------------------------------------------------------------------------------
Title "Creating shortcuts"
$shell = New-Object -ComObject WScript.Shell
foreach ($dir in @([Environment]::GetFolderPath("Desktop"), (Join-Path ([Environment]::GetFolderPath("Programs")) ""))) {
    $lnk = $shell.CreateShortcut((Join-Path $dir "OoTCraft.lnk"))
    $lnk.TargetPath = $soh
    $lnk.WorkingDirectory = Split-Path $soh
    $lnk.IconLocation = "$soh,0"
    $lnk.Description = "OoTCraft: Ocarina of Time as Steve"
    $lnk.Save()
}
Ok "Desktop and Start menu shortcuts: OoTCraft"

Title "All set!"
Info "1. Double-click OoTCraft on your desktop."
Info "2. The first time, Ship of Harkinian asks for YOUR Ocarina of Time ROM and builds its game data from it."
Info "3. Load a save file. Your Minecraft Launcher opens: pick the OoTCraft profile and press Play."
Info "   Minecraft hides itself once it connects and you're Steve in Hyrule."
Info ""
Info "Help, updates and friends: https://discord.gg/w5sxzdPtT7"
