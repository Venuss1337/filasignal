<#
.SYNOPSIS
    Runs the FilaSignal performance benchmarks and writes a self-contained result folder.

.DESCRIPTION
    Results go to benchmark-results/<timestamp>-<profile>/: SUMMARY.md (environment and every table), results.csv,
    the raw JSON of each part and the Gradle console logs. Nothing needs to be watched while it runs.

    The server benchmark runs inside the GameTest server, in the disposable world under
    filasignal-fabric/build/run/gameTest, which is deleted before every run. It never opens or changes any other world.

    The GameTest server does not wait between ticks, so a run measures thousands of ticks in seconds. Mean, median,
    P95, P99 and maximum MSPT are the real work per tick; "TPS (from MSPT)" is min(20, 1000 / MSPT), the rate the same
    work would allow on a paced server. Most of each run's wall time is Gradle and Minecraft startup.

    Profiles (warm-up / measured ticks per scenario; wall time is roughly one to three minutes each):
      quick     language + idle and small (10 devices, 3 programs)                    100 / 400
      normal    language + idle, small, advanced (30/10) and large (100/30)           200 / 1200
      large     idle, large and multiplayer (300 devices, 100 programs), 4 GB heap    300 / 2400
      stress    idle, multiplayer and stress (900 devices, 500 programs), 6 GB heap   300 / 2400
      extended  language + every scenario except stress, long measurements            600 / 6000
      client    editor timings from the client GameTest; opens a game window

.EXAMPLE
    .\tools\benchmarks\Run-Benchmarks.ps1 -Profile quick
.EXAMPLE
    .\tools\benchmarks\Run-Benchmarks.ps1 -Profile large -MeasureTicks 6000
#>
param(
    [ValidateSet('quick', 'normal', 'large', 'stress', 'extended', 'client')]
    [string] $Profile = 'quick',
    # Override the profile's warm-up and measurement lengths, in server ticks (20 per second).
    [int] $WarmupTicks = 0,
    [int] $MeasureTicks = 0,
    # Language benchmark iterations per workload.
    [int] $Iterations = 0
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $root "benchmark-results\$stamp-$Profile"
New-Item -ItemType Directory -Force $out | Out-Null
$gradle = Join-Path $root 'gradlew.bat'

$settings = @{
    quick    = @{ Server = 'quick';  Warmup = 100; Measure = 400;  Heap = '';   Language = 100 }
    normal   = @{ Server = 'normal'; Warmup = 200; Measure = 1200; Heap = '';   Language = 200 }
    large    = @{ Server = 'large';  Warmup = 300; Measure = 2400; Heap = '4g'; Language = 0 }
    stress   = @{ Server = 'stress'; Warmup = 300; Measure = 2400; Heap = '6g'; Language = 0 }
    extended = @{ Server = 'idle,small,advanced,large,multiplayer'; Warmup = 600; Measure = 6000; Heap = '4g'; Language = 1000 }
    client   = @{ Server = '';       Warmup = 0;   Measure = 0;    Heap = '';   Language = 0 }
}[$Profile]
if ($WarmupTicks -gt 0) { $settings.Warmup = $WarmupTicks }
if ($MeasureTicks -gt 0) { $settings.Measure = $MeasureTicks }
if ($Iterations -gt 0) { $settings.Language = $Iterations }

function Invoke-Gradle([string] $log, [string[]] $arguments) {
    & $gradle @arguments --console=plain *> (Join-Path $out $log)
    return $LASTEXITCODE
}

# Environment, recorded before anything runs.
$build = Get-Content (Join-Path $root 'filasignal-fabric\build.gradle.kts') -Raw
function Get-Pinned([string] $name) { if ($build -match "val $name = `"([^`"]+)`"") { $Matches[1] } else { 'unknown' } }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1)
$memory = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
$dirty = if (git -C $root status --porcelain) { ' (uncommitted changes present)' } else { '' }
$environment = @(
    "- Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
    "- Commit: $(git -C $root rev-parse --short HEAD)$dirty",
    "- Profile: $Profile",
    "- CPU: $($cpu.Name.Trim()) ($($cpu.NumberOfCores) cores, $($cpu.NumberOfLogicalProcessors) threads)",
    "- Memory: $memory GB",
    "- OS: $((Get-CimInstance Win32_OperatingSystem).Caption)",
    "- Java: $((& $java -version 2>&1 | Select-Object -First 1))",
    "- Minecraft $(Get-Pinned 'minecraftVersion'), Fabric Loader $(Get-Pinned 'loaderVersion'), Fabric API $(Get-Pinned 'fabricApiVersion')",
    "- Server heap limit: $(if ($settings.Heap) { $settings.Heap } else { 'Gradle default' })"
)

$sections = @()
$csv = @('part,scenario,metric,value')
$failed = @()

if ($settings.Language -gt 0) {
    Write-Host "Language benchmark ($($settings.Language) iterations)..."
    $exit = Invoke-Gradle 'language-console.log' @(':filascript-core:languageBenchmark', "-PfilaBenchOut=$out", "-PfilaBenchIterations=$($settings.Language)")
    if ($exit -ne 0) { $failed += 'language benchmark' }
    $file = Join-Path $out 'language-results.json'
    if (Test-Path $file) {
        $sections += @('## Language (interpreter outside Minecraft)', '', (Get-Content (Join-Path $out 'language-results.md') -Raw), '')
        foreach ($w in (Get-Content $file -Raw | ConvertFrom-Json).workloads) {
            foreach ($metric in 'operations', 'meanMs', 'medianMs', 'p95Ms', 'nsPerOperation') {
                if ($null -ne $w.$metric) { $csv += "language,`"$($w.name)`",$metric,$($w.$metric)" }
            }
        }
    }
}

if ($settings.Server) {
    Write-Host "Server benchmark ($($settings.Server); warm-up $($settings.Warmup), measured $($settings.Measure) ticks)..."
    $arguments = @(':filasignal-fabric:runGameTest', '-PfilaTestFilter=filasignal-gametest:*server_benchmark*',
        "-PfilaTestReport=$(Join-Path $out 'server-report.xml')", "-PfilaBenchProfile=$($settings.Server)",
        "-PfilaBenchOut=$out", "-PfilaBenchWarmup=$($settings.Warmup)", "-PfilaBenchTicks=$($settings.Measure)")
    if ($settings.Heap) { $arguments += "-PfilaHeap=$($settings.Heap)" }
    $exit = Invoke-Gradle 'server-console.log' $arguments
    if ($exit -ne 0) { $failed += 'server benchmark' }
    $file = Join-Path $out 'server-results.json'
    if (Test-Path $file) {
        $data = Get-Content $file -Raw | ConvertFrom-Json
        $sections += @('## Server (dedicated GameTest server, whole tick)', '',
            "Warm-up $($data.warmupTicks) ticks, measured $($data.measuredTicks) ticks per scenario. World: the disposable GameTest world.", '',
            (Get-Content (Join-Path $out 'server-results.md') -Raw), '',
            '"FilaSignal" columns are the program scheduler plus the device runtime per tick; the rest of the tick is Minecraft.',
            'Faults are handler failures reported by programs, such as refused host calls. In the stress scenario they are',
            'expected: hundreds of detector programs start together, their timers line up, and the 16 fresh entity scans per',
            'tick refuse the rest with detector.busy. The log shows the first ones; later ones are rate-limited.',
            "JVM arguments: ``$($data.jvmArguments)``", '')
        foreach ($s in $data.scenarios) {
            foreach ($p in $s.PSObject.Properties) {
                if ($p.Name -ne 'name') { $csv += "server,$($s.name),$($p.Name),$($p.Value)" }
            }
        }
    }
}

if ($Profile -eq 'client') {
    Write-Host 'Client editor benchmark (a game window opens)...'
    $exit = Invoke-Gradle 'client-console.log' @(':filasignal-fabric:runClientGameTest', '-PfilaClientTests=true')
    if ($exit -ne 0) { $failed += 'client GameTest' }
    $lines = Select-String -Path (Join-Path $out 'client-console.log') -Pattern 'filasignal/editor-test.*?: (.*)$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value }
    $sections += @('## Client editor', '', 'Wall times measured inside the client GameTest (a single sample unless stated).', '')
    $sections += if ($lines) { $lines | ForEach-Object { "- $_" } } else { '- No editor timings were found in the log.' }
    $sections += @('', 'World rendering (FPS) is not measured automatically; see docs/engineering/BENCHMARKS.md.', '')
}

$status = if ($failed.Count -eq 0) { 'All parts completed.' } else { "Failed: $($failed -join ', '). See the console logs in this folder." }
$summary = @("# FilaSignal benchmark: $Profile", '', $status, '', '## Environment', '') + $environment + @('') + $sections
$summary | Set-Content -Encoding utf8 (Join-Path $out 'SUMMARY.md')
$csv | Set-Content -Encoding utf8 (Join-Path $out 'results.csv')
Write-Host $status
Write-Host "Summary: $(Join-Path $out 'SUMMARY.md')"
if ($failed.Count -gt 0) { exit 1 }
