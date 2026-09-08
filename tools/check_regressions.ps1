$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$javaRoot = Join-Path $root "src\main\java"

function Find-Text {
    param([string]$Pattern, [string]$Path)
    $rg = Get-Command rg -ErrorAction SilentlyContinue
    if ($null -ne $rg) {
        return @(& $rg.Source -n $Pattern $Path 2>$null)
    }
    # GitHub's Windows runner does not guarantee ripgrep. Select-String keeps
    # the same regular-expression semantics for this small static check.
    $files = if (Test-Path -LiteralPath $Path -PathType Leaf) {
        @(Get-Item -LiteralPath $Path)
    } else {
        @(Get-ChildItem -LiteralPath $Path -Recurse -File)
    }
    return @($files | Select-String -Pattern $Pattern | ForEach-Object {
        "{0}:{1}:{2}" -f $_.Path, $_.LineNumber, $_.Line.Trim()
    })
}

function Assert-NoMatch {
    param([string]$Pattern, [string]$Path, [string]$Description)
    $matches = @(Find-Text $Pattern $Path)
    if ($matches.Count -gt 0) {
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
$aspectInjections = @(Find-Text "getActualAspect" $javaRoot)
$aspectCount = ($aspectInjections | Select-String "Inject\(method = .getActualAspect").Count
if ($aspectCount -gt 1) { throw "multiple getActualAspect injections: $aspectCount" }
Write-Host "Static regression checks passed."
