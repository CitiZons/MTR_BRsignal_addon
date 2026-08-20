$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$clientMods = "D:\Minecraft\CitiZons\10.alpha\C10.alpha.4.client\.minecraft\mods"

# Resolve archive name from gradle.properties (mod_id + mod_version)
$props = @{}
Get-Content (Join-Path $projectRoot "gradle.properties") | ForEach-Object {
	if ($_ -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.+?)\s*$') {
		$props[$matches[1]] = $matches[2]
	}
}
$modId = $props["mod_id"]
$modVersion = $props["mod_version"]
if (!$modId -or !$modVersion) {
	throw "Could not read mod_id / mod_version from gradle.properties"
}
$jarName = "$modId-$modVersion.jar"
$sourceJar = Join-Path $projectRoot "build\libs\$jarName"
$targetJar = Join-Path $clientMods $jarName

Push-Location $projectRoot
try {
	& (Join-Path $projectRoot "gradlew.bat") build --no-daemon
	if ($LASTEXITCODE -ne 0) {
		throw "Gradle build failed with exit code $LASTEXITCODE"
	}

	if (!(Test-Path -LiteralPath $sourceJar)) {
		throw "Build output not found: $sourceJar"
	}

	Copy-Item -LiteralPath $sourceJar -Destination $targetJar -Force
	$sourceHash = (Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash
	$targetHash = (Get-FileHash -LiteralPath $targetJar -Algorithm SHA256).Hash
	if ($sourceHash -ne $targetHash) {
		throw "Deployment hash mismatch: source $sourceHash != target $targetHash"
	}
	Get-Item -LiteralPath $targetJar | Select-Object FullName, Length, LastWriteTime
	Write-Host "SHA256: $targetHash"
} finally {
	Pop-Location
}
