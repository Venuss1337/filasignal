<#
.SYNOPSIS
    Checks the built mod JAR and the installed FilaScript CLI distribution.

.DESCRIPTION
    Run after `gradlew build :filascript-cli:installDist` (Verify-All.ps1 does both). Checks that the mod JAR has
    expanded metadata, the nested core library and LibGui, every asset and data folder, the packaged starters and the
    license, and that no test code, sources or development files leaked into it. Then runs the installed CLI on
    examples/hello.fsig. Writes verification-results/package-<timestamp>/SUMMARY.md and the JAR listing.
#>
param(
    [string] $OutDir = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $OutDir) { $OutDir = Join-Path $root "verification-results\package-$(Get-Date -Format 'yyyyMMdd-HHmmss')" }
New-Item -ItemType Directory -Force $OutDir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

$results = @()
function Test-Item([string] $name, [bool] $ok, [string] $detail = '') {
    $script:results += [pscustomobject]@{ Check = $name; Ok = $ok; Detail = $detail }
}

$jar = Get-ChildItem (Join-Path $root 'filasignal-fabric\build\libs') -Filter 'filasignal-*.jar' |
    Where-Object { $_.Name -notmatch '-(sources|dev)\.jar$' } | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $jar) { throw 'No mod JAR found. Run gradlew build first.' }
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $entries = $zip.Entries | ForEach-Object FullName
    $entries | Set-Content -Encoding utf8 (Join-Path $OutDir 'jar-contents.txt')
    $reader = New-Object System.IO.StreamReader(($zip.GetEntry('fabric.mod.json')).Open())
    $metadata = $reader.ReadToEnd(); $reader.Close()
} finally { $zip.Dispose() }
$mod = $metadata | ConvertFrom-Json

Test-Item 'fabric.mod.json present and fully expanded' ($metadata -notmatch '\$\{') "version $($mod.version)"
Test-Item 'Mod id is filasignal' ($mod.id -eq 'filasignal')
$rootBuild = Get-Content (Join-Path $root 'build.gradle.kts') -Raw
$version = if ($rootBuild -match 'val releaseVersion = "([^"]+)"') { $Matches[1] } else { '' }
Test-Item 'Mod version matches the project version' ($version -and $mod.version -eq $version) "project $version, mod $($mod.version)"
Test-Item 'JAR name carries the version and Minecraft version' ($jar.Name -eq "filasignal-$version+mc$($mod.depends.minecraft).jar") $jar.Name
Test-Item 'License is GPL-3.0-only' ($mod.license -eq 'GPL-3.0-only') $mod.license
Test-Item 'Description is not a placeholder' ($mod.description -and $mod.description -notmatch 'bootstrap') $mod.description
Test-Item 'Depends on Minecraft, Fabric Loader, Fabric API, LibGui and Java 25' (
    $mod.depends.minecraft -and $mod.depends.fabricloader -and $mod.depends.'fabric-api' -and $mod.depends.libgui -and $mod.depends.java) `
    "minecraft $($mod.depends.minecraft), loader $($mod.depends.fabricloader), fabric-api $($mod.depends.'fabric-api')"
$nested = $entries | Where-Object { $_ -like 'META-INF/jars/*.jar' }
Test-Item 'Nested FilaScript core library' ([bool]($nested -like 'META-INF/jars/filascript-core-*')) ($nested -join ', ')
Test-Item 'Nested LibGui' ([bool]($nested -like 'META-INF/jars/LibGui-*'))
Test-Item 'License and third-party notices included' (($entries -contains 'LICENSE_filasignal') -and ($entries -contains 'THIRD-PARTY-NOTICES_filasignal.md'))
Test-Item 'Mod initializer and client entry point' (
    ($entries -contains 'dev/wiktorfila/filasignal/fabric/FilaSignal.class') -and
    ($entries -contains 'dev/wiktorfila/filasignal/fabric/editor/EditorClient.class'))
foreach ($folder in 'assets/filasignal/blockstates/', 'assets/filasignal/models/', 'assets/filasignal/textures/',
        'assets/filasignal/items/', 'assets/filasignal/lang/', 'data/filasignal/recipe/', 'data/filasignal/loot_table/',
        'data/filasignal/advancement/', 'data/minecraft/tags/') {
    $count = @($entries | Where-Object { $_.StartsWith($folder) -and -not $_.EndsWith('/') }).Count
    Test-Item "Resources in $folder" ($count -gt 0) "$count files"
}
$starters = @(Get-ChildItem (Join-Path $root 'examples') -Filter 'starter-*.fsig').Count
$packaged = @($entries | Where-Object { $_ -like 'assets/filasignal/starters/*.fsig' }).Count
Test-Item 'Every starter program packaged' ($packaged -eq $starters -and $starters -gt 0) "$packaged of $starters"
$leaks = $entries | Where-Object { $_ -match '\.java$|gametest|GameTest|/test/|^run/|screenshots|\.fsig\.bak|\.DS_Store|Thumbs\.db' }
Test-Item 'No test code, sources or development files' (-not $leaks) ($leaks -join ', ')
Test-Item 'JAR size is reasonable (under 5 MB)' ($jar.Length -lt 5MB) ("{0:N0} KB" -f ($jar.Length / 1KB))

# CLI distribution.
$cli = Join-Path $root 'filascript-cli\build\install\filascript-cli\bin\filascript-cli.bat'
if (Test-Path $cli) {
    $output = & $cli run (Join-Path $root 'examples\hello.fsig') 2>&1 | Out-String
    Test-Item 'Installed CLI runs examples/hello.fsig' ($LASTEXITCODE -eq 0 -and $output -match 'Hello, FilaScript!') $output.Trim()
    $versionOutput = & $cli --version 2>&1 | Out-String
    Test-Item 'CLI reports the project version' ($versionOutput -match "FilaScript $([regex]::Escape($version))") $versionOutput.Trim()
    $install = Join-Path $root 'filascript-cli\build\install\filascript-cli'
    Test-Item 'CLI distribution has the license, README and example' (
        (Test-Path (Join-Path $install 'LICENSE')) -and (Test-Path (Join-Path $install 'README.md')) -and
        (Test-Path (Join-Path $install 'examples\hello.fsig')))
    $libs = Get-ChildItem (Join-Path $root 'filascript-cli\build\install\filascript-cli\lib') | ForEach-Object Name
    Test-Item 'CLI distribution has only the CLI and core libraries' (@($libs).Count -eq 2) ($libs -join ', ')
} else {
    Test-Item 'Installed CLI present' $false 'Run gradlew :filascript-cli:installDist first.'
}

$failed = @($results | Where-Object { -not $_.Ok })
$summary = @('# Package check', '', "- JAR: ``$($jar.Name)`` ($('{0:N0}' -f $jar.Length) bytes)",
    "- Commit: $(git -C $root rev-parse --short HEAD)", "- Result: $(if ($failed) { "$($failed.Count) failed" } else { 'all passed' })", '',
    '| Check | Result | Detail |', '| --- | --- | --- |')
$summary += $results | ForEach-Object { "| $($_.Check) | $(if ($_.Ok) { 'pass' } else { '**FAIL**' }) | $(($_.Detail -replace '\r?\n', ' ') -replace '\|', '/') |" }
$summary | Set-Content -Encoding utf8 (Join-Path $OutDir 'SUMMARY.md')
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host "Summary: $(Join-Path $OutDir 'SUMMARY.md')"
if ($failed) { exit 1 }
