$ErrorActionPreference = "Stop"

$base = "D:\Minecraft\CitiZons\10.alpha\mods_edit\MTR_BRsignal_addon\src\main\resources\assets\mtr_brsignal_addon"
$bbPath = Join-Path $base "models\block\indicator_1.bbmodel"
$objPath = Join-Path $base "models\block\indicator_1.obj"
$mtlPath = Join-Path $base "models\block\indicator_1.mtl"

$bb = Get-Content $bbPath -Raw | ConvertFrom-Json

$textureNames = @{}
$k = 0
foreach ($t in $bb.textures) {
	$textureNames[$k.ToString()] = ($t.name -replace "\.png$", "")
	$k++
}

$skipKey = $null
foreach ($key in $textureNames.Keys) {
	if ($textureNames[$key] -eq "indicator_1_route_1") {
		$skipKey = $key
	}
}

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("mtllib indicator_1.mtl")
$vi = 1

foreach ($e in $bb.elements) {
	$isRoute = $false
	foreach ($dir in @("north", "south", "east", "west", "up", "down")) {
		$f = $e.faces.$dir
		if ($null -ne $f -and $null -ne $f.texture -and $f.texture.ToString() -eq $skipKey) {
			$isRoute = $true
		}
	}
	if ($isRoute) {
		continue
	}

	# Forge OBJ 方块模型坐标采用 0..16 每格（直接使用 bbmodel 单位），x 平移 +8 使面板居中
	$x1 = [double]$e.from[0] + 8.0
	$x2 = [double]$e.to[0] + 8.0
	$y1 = [double]$e.from[1]
	$y2 = [double]$e.to[1]
	$z1 = [double]$e.from[2]
	$z2 = [double]$e.to[2]

	foreach ($dir in @("north", "south", "east", "west", "up", "down")) {
		$f = $e.faces.$dir
		if ($null -eq $f -or $null -eq $f.texture) {
			continue
		}
		$texName = $textureNames[$f.texture.ToString()]
		$u1 = [double]$f.uv[0]
		$v1 = [double]$f.uv[1]
		$u2 = [double]$f.uv[2]
		$v2 = [double]$f.uv[3]

		$corners = $null
		$nx = 0.0
		$ny = 0.0
		$nz = 0.0
		switch ($dir) {
			"south" { $corners = @(@($x1, $y1, $z2), @($x2, $y1, $z2), @($x2, $y2, $z2), @($x1, $y2, $z2)); $nz = 1.0 }
			"north" { $corners = @(@($x2, $y1, $z1), @($x1, $y1, $z1), @($x1, $y2, $z1), @($x2, $y2, $z1)); $nz = -1.0 }
			"east" { $corners = @(@($x2, $y1, $z1), @($x2, $y1, $z2), @($x2, $y2, $z2), @($x2, $y2, $z1)); $nx = 1.0 }
			"west" { $corners = @(@($x1, $y1, $z2), @($x1, $y1, $z1), @($x1, $y2, $z1), @($x1, $y2, $z2)); $nx = -1.0 }
			"up" { $corners = @(@($x1, $y2, $z2), @($x2, $y2, $z2), @($x2, $y2, $z1), @($x1, $y2, $z1)); $ny = 1.0 }
			"down" { $corners = @(@($x1, $y1, $z1), @($x2, $y1, $z1), @($x2, $y1, $z2), @($x1, $y1, $z2)); $ny = -1.0 }
		}

		$vts = , @(($u1 / 32.0), ($v2 / 32.0))
		$vts += , @(($u2 / 32.0), ($v2 / 32.0))
		$vts += , @(($u2 / 32.0), ($v1 / 32.0))
		$vts += , @(($u1 / 32.0), ($v1 / 32.0))

		[void]$sb.AppendLine("usemtl $texName")
		$idx = @()
		for ($i = 0; $i -lt 4; $i++) {
			$c = $corners[$i]
			$vt = $vts[$i]
			[void]$sb.AppendLine(("v {0:F6} {1:F6} {2:F6}" -f $c[0], $c[1], $c[2]))
			[void]$sb.AppendLine(("vt {0:F6} {1:F6}" -f $vt[0], $vt[1]))
			[void]$sb.AppendLine(("vn {0} {1} {2}" -f $nx, $ny, $nz))
			$idx += $vi
			$vi++
		}
		[void]$sb.AppendLine("f $($idx[0])/$($idx[0])/$($idx[0]) $($idx[1])/$($idx[1])/$($idx[1]) $($idx[2])/$($idx[2])/$($idx[2])")
		[void]$sb.AppendLine("f $($idx[0])/$($idx[0])/$($idx[0]) $($idx[2])/$($idx[2])/$($idx[2]) $($idx[3])/$($idx[3])/$($idx[3])")
	}
}

[System.IO.File]::WriteAllText($objPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

$mtl = New-Object System.Text.StringBuilder
foreach ($name in @("indicator_1", "grey", "black", "indicator_1_back", "indicator_1_route_1")) {
	[void]$mtl.AppendLine("newmtl $name")
	[void]$mtl.AppendLine("Kd 1.000000 1.000000 1.000000")
	[void]$mtl.AppendLine("map_Kd mtr_brsignal_addon:block/$name")
	[void]$mtl.AppendLine("")
}
[System.IO.File]::WriteAllText($mtlPath, $mtl.ToString(), (New-Object System.Text.UTF8Encoding($false)))

Write-Output "obj lines: $((Get-Content $objPath).Count)"
Write-Output "v count: $((Select-String -Path $objPath -Pattern '^v ').Count)"
Write-Output "f count: $((Select-String -Path $objPath -Pattern '^f ').Count)"
