<#
.SYNOPSIS
    The release-candidate verification entry point: runs the automated checks and writes one summary.

.DESCRIPTION
    Each step logs to verification-results/verify-<timestamp>/<step>.log; SUMMARY.md lists every step with its
    result, duration and test counts. Steps continue after a failure so one run shows everything; -From reruns from a
    named step, and -Only runs selected steps.

    Levels:
      quick  core tests, Fabric unit tests, build, CLI distribution, package check          (~2 min)
      full   quick + server GameTests (API catalog freshness, Minecraft examples, security) + documentation site
    -Client adds the client GameTests (opens a game window). -CleanServer adds the packaged dedicated-server smoke test;
    it needs internet access and an accepted EULA (see Start-CleanServer.ps1), passed with -ReuseEulaFrom or -AcceptEula.

.EXAMPLE
    .\tools\verify\Verify-All.ps1
.EXAMPLE
    .\tools\verify\Verify-All.ps1 -Level full -Client
.EXAMPLE
    .\tools\verify\Verify-All.ps1 -Only gametests
#>
param(
    [ValidateSet('quick', 'full')]
    [string] $Level = 'quick',
    [switch] $Client,
    [switch] $CleanServer,
    [string] $ReuseEulaFrom = '',
    [switch] $AcceptEula,
    # Step names: core, fabric-unit, build, package, gametests, docs, client, clean-server.
    [string[]] $Only = @(),
    [string] $From = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $root "verification-results\verify-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Force $out | Out-Null
$gradle = Join-Path $root 'gradlew.bat'

function Get-JUnitCounts([string] $pattern) {
    $tests = 0; $failures = 0; $skipped = 0
    foreach ($file in Get-ChildItem $pattern -ErrorAction SilentlyContinue) {
        [xml] $xml = Get-Content $file.FullName -Raw
        foreach ($case in $xml.SelectNodes('//testcase')) {
            $tests++
            if ($case.SelectSingleNode('failure|error')) { $failures++ }
            elseif ($case.SelectSingleNode('skipped')) { $skipped++ }
        }
    }
    if ($tests -eq 0) { return '' }
    return "$tests tests, $failures failed, $skipped skipped"
}

$steps = [ordered]@{
    'core' = @{ Run = { & $gradle ':filascript-core:test' '--rerun' '--console=plain' }
                Counts = { Get-JUnitCounts (Join-Path $root 'filascript-core\build\test-results\test\*.xml') } }
    'fabric-unit' = @{ Run = { & $gradle ':filasignal-fabric:test' '--rerun' '--console=plain' }
                       Counts = { Get-JUnitCounts (Join-Path $root 'filasignal-fabric\build\test-results\test\*.xml') } }
    'build' = @{ Run = { & $gradle 'build' ':filascript-cli:installDist' '-x' 'test' '-x' 'runGameTest' '--console=plain' } }
    'package' = @{ Run = { & (Join-Path $PSScriptRoot 'Check-Package.ps1') -OutDir (Join-Path $out 'package') } }
}
if ($Level -eq 'full') {
    $steps['gametests'] = @{
        Run = { & $gradle ':filasignal-fabric:runGameTest' '--console=plain' "-PfilaTestReport=$(Join-Path $out 'gametests.xml')" }
        Counts = { Get-JUnitCounts (Join-Path $out 'gametests.xml') } }
    $steps['docs'] = @{ Run = {
        if (-not (Get-Command bun -ErrorAction SilentlyContinue)) { throw 'Bun is not installed; the documentation site was not built.' }
        Push-Location (Join-Path $root 'web')
        try { bun install --frozen-lockfile; if ($LASTEXITCODE -eq 0) { bun --bun run build } } finally { Pop-Location }
    } }
}
if ($Client) {
    $steps['client'] = @{ Run = { & $gradle ':filasignal-fabric:runClientGameTest' '-PfilaClientTests=true' '--console=plain' } }
}
if ($CleanServer) {
    $steps['clean-server'] = @{ Run = {
        $arguments = @{ Smoke = $true; Directory = (Join-Path $out 'clean-server') }
        if ($ReuseEulaFrom) { $arguments.ReuseEulaFrom = $ReuseEulaFrom }
        if ($AcceptEula) { $arguments.AcceptEula = $true }
        & (Join-Path $PSScriptRoot 'Start-CleanServer.ps1') @arguments
    } }
}

$names = @($steps.Keys)
if ($Only.Count -gt 0) { $names = $names | Where-Object { $Only -contains $_ } }
if ($From) { $names = $names[([array]::IndexOf($names, $From))..($names.Count - 1)] }

$rows = @()
$failed = @()
foreach ($name in $names) {
    $step = $steps[$name]
    Write-Host "== $name"
    $started = Get-Date
    $log = Join-Path $out "$name.log"
    $ok = $true
    try {
        $global:LASTEXITCODE = 0
        & $step.Run *> $log
        if ($LASTEXITCODE -ne 0) { $ok = $false }
    } catch {
        $ok = $false
        Add-Content $log "Step failed: $_"
    }
    $seconds = [int]((Get-Date) - $started).TotalSeconds
    $counts = if ($step.Counts) { & $step.Counts } else { '' }
    if (-not $ok) { $failed += $name }
    $rows += "| $name | $(if ($ok) { 'pass' } else { '**FAIL**' }) | $seconds s | $counts | ``$name.log`` |"
    Write-Host "   $(if ($ok) { 'pass' } else { 'FAIL' }) ($seconds s) $counts"
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$dirty = if (git -C $root status --porcelain) { ' (uncommitted changes present)' } else { '' }
$summary = @('# Verification summary', '',
    "- Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')", "- Commit: $(git -C $root rev-parse --short HEAD)$dirty",
    "- Level: $Level$(if ($Client) { ' + client' })$(if ($CleanServer) { ' + clean server' })",
    "- Java: $((& $java -version 2>&1 | Select-Object -First 1))",
    "- Result: $(if ($failed) { "failed: $($failed -join ', ')" } else { 'all steps passed' })", '',
    '| Step | Result | Time | Tests | Log |', '| --- | --- | --- | --- | --- |') + $rows
if ($failed) {
    $summary += @('', "Rerun a step with ``.\tools\verify\Verify-All.ps1 -Level $Level -Only <step>``.")
}
$summary | Set-Content -Encoding utf8 (Join-Path $out 'SUMMARY.md')
Write-Host "Summary: $(Join-Path $out 'SUMMARY.md')"
if ($failed) { exit 1 }
