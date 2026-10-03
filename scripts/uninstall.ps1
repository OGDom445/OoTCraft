# OoTCraft uninstaller: removes the OoTCraft profile from your Minecraft Launcher and the shortcuts.
# Your OoTCraft worlds stay in %APPDATA%\.ootcraft unless you pass -RemoveWorlds.
param([switch]$RemoveWorlds)
$ErrorActionPreference = "Stop"
$mcDir = Join-Path $env:APPDATA ".minecraft"
foreach ($file in @("launcher_profiles.json", "launcher_profiles_microsoft_store.json")) {
    $path = Join-Path $mcDir $file
    if (-not (Test-Path $path)) { continue }
    $json = Get-Content $path -Raw | ConvertFrom-Json
    if ($json.profiles.PSObject.Properties.Name -contains "ootcraft") {
        $json.profiles.PSObject.Properties.Remove("ootcraft")
        [IO.File]::WriteAllText($path, ($json | ConvertTo-Json -Depth 32), (New-Object Text.UTF8Encoding($false)))
        Write-Host "Removed the OoTCraft profile from $file"
    }
}
foreach ($dir in @([Environment]::GetFolderPath("Desktop"), [Environment]::GetFolderPath("Programs"))) {
    $lnk = Join-Path $dir "OoTCraft.lnk"
    if (Test-Path $lnk) { Remove-Item $lnk; Write-Host "Removed $lnk" }
}
if ($RemoveWorlds) {
    Remove-Item (Join-Path $env:APPDATA ".ootcraft") -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Removed %APPDATA%\.ootcraft"
}
Write-Host "OoTCraft uninstalled. Delete this folder to remove Ship of Harkinian too."
