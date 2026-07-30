param(
    [int]$WarmupFrames = 120,
    [int]$BenchmarkFrames = 240,
    [long]$Seed = 20260730,
    [string]$OutputDirectory = "build/performance-final"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$resolvedOutput = Join-Path $projectRoot $OutputDirectory
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null

& (Join-Path $projectRoot "gradlew.bat") installDist
if ($LASTEXITCODE -ne 0) {
    throw "installDist failed with exit code $LASTEXITCODE"
}

$launcher = Join-Path $projectRoot "build/install/gravity/bin/gravity.bat"
$cases = @(
    @{ Name = "calm-050k"; Bodies = 50000; Scenario = "calm"; Extra = @() },
    @{ Name = "calm-100k"; Bodies = 100000; Scenario = "calm"; Extra = @() },
    @{ Name = "calm-400k"; Bodies = 400000; Scenario = "calm"; Extra = @() },
    @{ Name = "square-400k"; Bodies = 400000; Scenario = "calm"; Extra = @("--square-test=632") },
    @{ Name = "projectile-100k"; Bodies = 100000; Scenario = "projectile"; Extra = @() },
    @{ Name = "projectile-400k"; Bodies = 400000; Scenario = "projectile"; Extra = @() }
)

foreach ($case in $cases) {
    $arguments = @(
        "--bodies=$($case.Bodies)",
        "--capacity=420000",
        "--seed=$Seed",
        "--benchmark=$($case.Scenario)",
        "--warmup-frames=$WarmupFrames",
        "--benchmark-frames=$BenchmarkFrames",
        "--adaptive-collisions=true"
    ) + $case.Extra
    $log = Join-Path $resolvedOutput "$($case.Name).log"
    Write-Host "Running $($case.Name) -> $log"
    & $launcher @arguments 2>&1 | Tee-Object -FilePath $log
    if ($LASTEXITCODE -ne 0) {
        throw "Benchmark $($case.Name) failed with exit code $LASTEXITCODE"
    }
}
