<#
.SYNOPSIS
    Prepares a clean dedicated server with the packaged FilaSignal JAR, and optionally runs a smoke test.

.DESCRIPTION
    Creates a new folder (default verification-results/clean-server-<timestamp>) containing only the official Fabric
    server launcher for the pinned Minecraft and Loader versions, Fabric API from the Fabric Maven, and the mod JAR
    from filasignal-fabric/build/libs (run gradlew build first). Nothing from the development run directory is used,
    so this checks the packaged mod as a player or server owner would install it. Needs internet access.

    It never opens or changes an existing world and never edits an existing eula.txt. The new server starts only if
    the Minecraft EULA (https://aka.ms/MinecraftEULA) is accepted for the new folder, either by -AcceptEula (you accept
    it now) or by -ReuseEulaFrom, which copies an eula.txt that you already accepted. Without either, the folder is
    prepared and the script stops.

    -Smoke starts the server headless, waits for startup, runs a few commands, stops it and checks the log. Without
    -Smoke the server runs interactively in this console for manual testing (the two-player walkthrough).

    -CopyWorldFrom copies an existing world folder into the new server as its world, to check that saves from an
    earlier build still load. Only the copy is opened; the original is never touched.

.EXAMPLE
    .\tools\verify\Start-CleanServer.ps1 -ReuseEulaFrom .\filasignal-fabric\run\eula.txt -Smoke
.EXAMPLE
    .\tools\verify\Start-CleanServer.ps1 -AcceptEula
#>
param(
    [string] $Directory = '',
    [switch] $AcceptEula,
    [string] $ReuseEulaFrom = '',
    [switch] $Smoke,
    [int] $Port = 25565,
    [string] $Memory = '2G',
    [int] $StartupSeconds = 240,
    [string] $CopyWorldFrom = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$build = Get-Content (Join-Path $root 'filasignal-fabric\build.gradle.kts') -Raw
function Get-Pinned([string] $name) {
    if ($build -match "val $name = `"([^`"]+)`"") { return $Matches[1] }
    throw "Could not read $name from filasignal-fabric/build.gradle.kts"
}
$minecraft = Get-Pinned 'minecraftVersion'
$loader = Get-Pinned 'loaderVersion'
$fabricApi = Get-Pinned 'fabricApiVersion'

$jar = Get-ChildItem (Join-Path $root 'filasignal-fabric\build\libs') -Filter 'filasignal-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-(sources|dev)\.jar$' } | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $jar) { throw 'No mod JAR found. Run .\gradlew.bat build first.' }

# A new, dedicated folder. An existing folder is reused only if this script created it.
$marker = '.filasignal-clean-server'
if (-not $Directory) { $Directory = Join-Path $root "verification-results\clean-server-$(Get-Date -Format 'yyyyMMdd-HHmmss')" }
if (Test-Path $Directory) {
    if (-not (Test-Path (Join-Path $Directory $marker)) -and (Get-ChildItem $Directory -Force | Select-Object -First 1)) {
        throw "$Directory is not empty and was not created by this script. Choose a new folder."
    }
} else {
    New-Item -ItemType Directory -Force $Directory | Out-Null
}
$Directory = (Resolve-Path $Directory).Path
Set-Content (Join-Path $Directory $marker) "Created by tools/verify/Start-CleanServer.ps1 for a disposable test server."
$mods = Join-Path $Directory 'mods'
New-Item -ItemType Directory -Force $mods | Out-Null

# Official downloads: Fabric Meta server launcher and Fabric API from the Fabric Maven.
# Invoke-RestMethod emits a JSON array as one object, so assign it before filtering.
$installers = Invoke-RestMethod 'https://meta.fabricmc.net/v2/versions/installer'
$installer = ($installers | Where-Object { $_.stable } | Select-Object -First 1).version
if (-not $installer) { throw 'Could not read the Fabric installer versions from meta.fabricmc.net.' }
$launcher = Join-Path $Directory 'fabric-server-launch.jar'
if (-not (Test-Path $launcher)) {
    Write-Host "Downloading the Fabric server launcher (Minecraft $minecraft, Loader $loader, installer $installer)..."
    Invoke-WebRequest "https://meta.fabricmc.net/v2/versions/loader/$minecraft/$loader/$installer/server/jar" -OutFile $launcher
}
$apiJar = Join-Path $mods "fabric-api-$fabricApi.jar"
if (-not (Test-Path $apiJar)) {
    Write-Host "Downloading Fabric API $fabricApi..."
    Invoke-WebRequest "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$fabricApi/fabric-api-$fabricApi.jar" -OutFile $apiJar
}
Get-ChildItem $mods -Filter 'filasignal-*.jar' | Remove-Item
Copy-Item $jar.FullName $mods

$properties = Join-Path $Directory 'server.properties'
if (-not (Test-Path $properties)) {
    @('level-name=filasignal-clean', "server-port=$Port", 'motd=FilaSignal clean test server', 'spawn-protection=0') |
        Set-Content -Encoding ascii $properties
}

if ($CopyWorldFrom) {
    if (-not (Test-Path (Join-Path $CopyWorldFrom 'level.dat'))) { throw "$CopyWorldFrom is not a world folder (no level.dat)." }
    $world = Join-Path $Directory 'filasignal-clean'
    if (Test-Path $world) { throw "The test server already has a world; use a new -Directory." }
    Copy-Item -Recurse $CopyWorldFrom $world
    Remove-Item (Join-Path $world 'session.lock') -ErrorAction SilentlyContinue
}

$eula = Join-Path $Directory 'eula.txt'
if ($AcceptEula) {
    'eula=true' | Set-Content -Encoding ascii $eula
} elseif ($ReuseEulaFrom) {
    if (-not ((Get-Content $ReuseEulaFrom -Raw) -match '(?m)^\s*eula\s*=\s*true\s*$')) { throw "$ReuseEulaFrom does not accept the EULA." }
    Copy-Item $ReuseEulaFrom $eula
}
if (-not ((Test-Path $eula) -and ((Get-Content $eula -Raw) -match '(?m)^\s*eula\s*=\s*true\s*$'))) {
    Write-Host "Prepared $Directory. The Minecraft EULA is not accepted for it, so the server was not started."
    Write-Host 'Rerun with -AcceptEula (after reading https://aka.ms/MinecraftEULA) or -ReuseEulaFrom <accepted eula.txt>.'
    exit 2
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$arguments = @("-Xmx$Memory", '-jar', 'fabric-server-launch.jar', 'nogui')
if (-not $Smoke) {
    Write-Host "Starting the server in $Directory on port $Port. Type 'stop' to end it."
    Push-Location $Directory
    try { & $java @arguments } finally { Pop-Location }
    exit $LASTEXITCODE
}

# Smoke test: start, wait for "Done", run commands, stop, check the log.
$info = New-Object System.Diagnostics.ProcessStartInfo $java
$info.Arguments = ($arguments -join ' ')
$info.WorkingDirectory = $Directory
$info.UseShellExecute = $false
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$process = [System.Diagnostics.Process]::Start($info)
$console = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
$outTask = $process.StandardOutput.ReadToEndAsync()
$errTask = $process.StandardError.ReadToEndAsync()
$latest = Join-Path $Directory 'logs\latest.log'
$deadline = (Get-Date).AddSeconds($StartupSeconds)
$started = $false
while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
    Start-Sleep -Seconds 2
    if ((Test-Path $latest) -and (Select-String -Path $latest -Pattern 'Done \(' -Quiet)) { $started = $true; break }
}
if ($started) {
    foreach ($command in 'list', 'filasignal drives', 'save-all', 'stop') {
        $process.StandardInput.WriteLine($command)
        Start-Sleep -Seconds 3
    }
}
if (-not $process.WaitForExit(120000)) { $process.Kill($true) }
$output = $outTask.Result + $errTask.Result
$output | Set-Content -Encoding utf8 (Join-Path $Directory 'smoke-console.txt')

$log = if (Test-Path $latest) { Get-Content $latest -Raw } else { '' }
$checks = [ordered]@{
    'Server reached "Done"' = $started
    'Fabric loaded FilaSignal' = ($log -match 'filasignal')
    'FilaSignal command answered' = ($log -match 'drive records? in this world')
    'Stopped normally' = ($log -match 'Stopping server' -and $process.ExitCode -eq 0)
    'No errors or exceptions in the log' = -not ($log -match '(?m)/ERROR\]|Exception')
}
$summary = @('# Clean dedicated-server smoke test', '', "- Folder: ``$Directory``", "- Mod JAR: ``$($jar.Name)``",
    "- Minecraft $minecraft, Fabric Loader $loader (installer $installer), Fabric API $fabricApi",
    "- EULA: $(if ($AcceptEula) { 'accepted with -AcceptEula' } else { "copied from $ReuseEulaFrom" })",
    "- World: $(if ($CopyWorldFrom) { "a copy of $CopyWorldFrom" } else { 'new' })", '',
    '| Check | Result |', '| --- | --- |')
$summary += $checks.GetEnumerator() | ForEach-Object { "| $($_.Key) | $(if ($_.Value) { 'pass' } else { '**FAIL**' }) |" }
$summary += @('', 'Server log: `logs/latest.log`; console: `smoke-console.txt`.')
$summary | Set-Content -Encoding utf8 (Join-Path $Directory 'SUMMARY.md')
$summary | Write-Host
if ($checks.Values -contains $false) { exit 1 }
