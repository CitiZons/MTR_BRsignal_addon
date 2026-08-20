$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$javaRoot = Join-Path $root "src\main\java"
$resourcesRoot = Join-Path $root "src\main\resources"

function Assert-NoMatch {
	param(
		[string]$Pattern,
		[string]$Path,
		[string]$Description
	)
	$matches = & rg -n $Pattern $Path 2>$null
	if ($LASTEXITCODE -eq 0 -and $matches) {
		Write-Host "REGRESSION: $Description"
		$matches | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
		throw "Regression check failed: $Description"
	}
}

Assert-NoMatch "BlockSignalBlockEntityMixin" $javaRoot "旧客户端 Aspect Mixin 不应存在"
Assert-NoMatch "getOpenRouteBindings|findNextSignalsOnRoutes|isSignalOnAnyRoute|getOccupied\(" (Join-Path $javaRoot "org\mtrbr\logic\SignalLogic.java") "SignalLogic 旧闭塞链符号不应存在"
Assert-NoMatch "MinecraftClientData\.getInstance\(\)\.vehicles" (Join-Path $javaRoot "org\mtrbr\logic") "客户端闭塞逻辑不得遍历 MinecraftClientData.vehicles"

$aspectInjections = & rg -n "getActualAspect" $javaRoot
$aspectCount = ($aspectInjections | Select-String "Inject\(method = .getActualAspect" ).Count
if ($aspectCount -gt 1) {
	throw "存在多个 getActualAspect Mixin 注入: $aspectCount"
}

Write-Host "Static regression checks passed."
