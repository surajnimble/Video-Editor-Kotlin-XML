$ErrorActionPreference = "Stop"
$out = "app\src\main\assets\luts"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$N = 17
$indices = 0..($N-1)

function Clamp($v) { if ($v -lt 0) { 0.0 } elseif ($v -gt 1) { 1.0 } else { $v } }
function Fmt([double]$v) { $v.ToString("0.000000", [Globalization.CultureInfo]::InvariantCulture) }

function WriteLut($filename, $title, $fx) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("TITLE $title")
    [void]$sb.AppendLine("LUT_3D_SIZE $N")
    foreach ($bIdx in $indices) {
        foreach ($gIdx in $indices) {
            foreach ($rIdx in $indices) {
                $r = $rIdx / ($N - 1)
                $g = $gIdx / ($N - 1)
                $b = $bIdx / ($N - 1)
                $rgb = & $fx $r $g $b
                [void]$sb.AppendLine((Fmt $rgb[0]) + " " + (Fmt $rgb[1]) + " " + (Fmt $rgb[2]))
            }
        }
    }
    [IO.File]::WriteAllText((Join-Path $out $filename), $sb.ToString())
    Write-Output "wrote $filename ($($N*$N*$N) entries)"
}

WriteLut "warm.cube" "Warm amber boost" {
    param($r,$g,$b)
    @((Clamp ($r*1.10 + 0.03)), (Clamp ($g*1.02)), (Clamp ($b*0.90)))
}

WriteLut "cool.cube" "Cool teal shift" {
    param($r,$g,$b)
    @((Clamp ($r*0.90)), (Clamp $g), (Clamp ($b*1.08 + 0.02)))
}

WriteLut "mono.cube" "Grayscale (luminance)" {
    param($r,$g,$b)
    $y = 0.2126*$r + 0.7152*$g + 0.0722*$b
    @($y,$y,$y)
}

WriteLut "cinema.cube" "Contrast S-curve + teal-orange" {
    param($r,$g,$b)
    $c = { param([double]$v) [math]::Min(1.0,[math]::Max(0.0,(($v - 0.5)*1.15 + 0.5))) }
    $r2 = & $c $r; $g2 = & $c $g; $b2 = & $c $b
    @((Clamp ($r2*1.04 + 0.02)), (Clamp $g2), (Clamp ($b2*1.06 - 0.02)))
}

WriteLut "vintage.cube" "Faded low-contrast sepia" {
    param($r,$g,$b)
    $c = { param([double]$v) [math]::Min(1.0,[math]::Max(0.0,(($v - 0.5)*0.85 + 0.5))) }
    $r2 = & $c $r; $g2 = & $c $g; $b2 = & $c $b
    @((Clamp ($r2*1.05)), (Clamp $g2), (Clamp ($b2*0.94)))
}
