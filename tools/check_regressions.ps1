$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$javaRoot = Join-Path $root "src\main\java"

function Assert-NoMatch {
    param([string]$Pattern, [string]$Path, [string]$Description)
    $matches = & rg -n $Pattern $Path 2>$null
    if ($LASTEXITCODE -eq 0 -and $matches) {
        Write-Host "REGRESSION: $Description"
        $matches | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
        throw "Regression check failed: $Description"
    }
}

Assert-NoMatch "BlockSignalBlockEntityMixin" $javaRoot "legacy client aspect mixin"
$signalLogic = Join-Path $javaRoot "org\mtrbr\logic\SignalLogic.java"
Assert-NoMatch "getOpenRouteBindings|findNextSignalsOnRoutes|isSignalOnAnyRoute|getOccupied\(" $signalLogic "legacy SignalLogic closure"
Assert-NoMatch "SignalLogic\.findAppliedNode|runtimeBlocks|SignalBlockSavedData\.rebuild" $javaRoot "runtime topology rebuild"
Assert-NoMatch "manualDrivingOverride.*NaN|oneShotOverride.*NaN" $javaRoot "override infinite boundary"
Assert-NoMatch "shouldDisableNativeBlock.*override|OVERRIDE_OCCUPIED" $javaRoot "override safety bypass"
$aspectInjections = & rg -n "getActualAspect" $javaRoot
$aspectCount = ($aspectInjections | Select-String "Inject\(method = .getActualAspect").Count
if ($aspectCount -gt 1) { throw "multiple getActualAspect injections: $aspectCount" }
Write-Host "Static regression checks passed."
