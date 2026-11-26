<#
.SYNOPSIS
    Runs the server GameTests several times and summarizes failures, for intermittent problems.

.DESCRIPTION
    Each run starts from a fresh test world, writes a JUnit report and, with -Trace, logs program activation,
    detachment and unloading. Results go to verification-results/gametest-repeat-<timestamp>/: SUMMARY.md, one
    report and one log per run. Failing tests' messages include chunk diagnostics.

.EXAMPLE
    .\tools\verify\Repeat-GameTests.ps1 -Runs 10 -Trace
.EXAMPLE
    .\tools\verify\Repeat-GameTests.ps1 -Runs 20 -Filter 'filasignal-gametest:*chunk_unload*'
#>
param(
    [int] $Runs = 5,
    # A Fabric test selection pattern, such as 'filasignal-gametest:*chunk_unload*'. Empty runs every test.
    [string] $Filter = '',
    [switch] $Trace
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $root "verification-results\gametest-repeat-$stamp"
New-Item -ItemType Directory -Force $out | Out-Null
$gradle = Join-Path $root 'gradlew.bat'
$commit = (git -C $root rev-parse --short HEAD)
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$rows = @()
$failures = @()

for ($i = 1; $i -le $Runs; $i++) {
    $report = Join-Path $out "run-$i.xml"
    $log = Join-Path $out "run-$i.log"
    $arguments = @(':filasignal-fabric:runGameTest', '--console=plain', "-PfilaTestReport=$report")
    if ($Filter) { $arguments += "-PfilaTestFilter=$Filter" }
    if ($Trace) { $arguments += '-PfilaTrace=true' }
    $started = Get-Date
    & $gradle @arguments *> $log
    $exit = $LASTEXITCODE
    $seconds = [int]((Get-Date) - $started).TotalSeconds
    $tests = 0; $failed = 0
    if (Test-Path $report) {
        [xml] $xml = Get-Content $report -Raw
        foreach ($case in $xml.SelectNodes('//testcase')) {
            $tests++
            $failure = $case.SelectSingleNode('failure')
            if ($failure) {
                $failed++
                $message = if ($failure.message) { $failure.message } else { $failure.InnerText }
                $failures += [pscustomobject]@{ Run = $i; Test = $case.name; Message = $message.Trim() }
            }
        }
    }
    $rows += "| $i | $exit | $tests | $failed | $seconds s |"
    Write-Host "Run $i of ${Runs}: exit $exit, $tests tests, $failed failed, $seconds s"
}

$summary = @(
    '# Server GameTest repeat run', '',
    "- Commit: $commit", "- Runs: $Runs", "- Filter: $(if ($Filter) { $Filter } else { 'all tests' })",
    "- Lifecycle trace: $($Trace.IsPresent)", "- Java: $((& $java -version 2>&1 | Select-Object -First 1))", '',
    '| Run | Exit | Tests | Failed | Time |', '| --- | --- | --- | --- | --- |'
) + $rows + @('', '## Failures', '')
if ($failures.Count -eq 0) {
    $summary += 'None.'
} else {
    foreach ($f in $failures) {
        $summary += "- Run $($f.Run), ``$($f.Test)``: $($f.Message)"
    }
    if ($Trace) {
        $summary += @('', 'Lifecycle trace lines of failing runs are in the matching `run-N.log` (search for `[lifecycle]`).')
    }
}
$summary | Set-Content -Encoding utf8 (Join-Path $out 'SUMMARY.md')
Write-Host "Summary: $(Join-Path $out 'SUMMARY.md')"
if ($failures.Count -gt 0) { exit 1 }
