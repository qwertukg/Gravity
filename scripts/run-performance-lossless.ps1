param(
    [int]$WarmupFrames = 120,
    [int]$BenchmarkFrames = 240,
    [int]$Repetitions = 2
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$cases = "calm-050k,calm-100k,calm-400k,square-400k,projectile-100k,projectile-400k,impact,galaxy"
Push-Location -LiteralPath $projectRoot
try {
    & .\gradlew.bat test gpuTest collisionBenchmark "--args=$WarmupFrames $BenchmarkFrames $cases $Repetitions"
    if ($LASTEXITCODE -ne 0) {
        throw "Collision verification/benchmark failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
