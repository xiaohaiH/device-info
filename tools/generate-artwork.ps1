# Build-time only. The APK contains the generated PNGs and no graphics dependency.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$res = Join-Path $PSScriptRoot '..\src\main\res'
foreach ($entry in @(@('mdpi',48), @('hdpi',72), @('xhdpi',96), @('xxhdpi',144), @('xxxhdpi',192))) {
    $size = [int]$entry[1]
    $dir = Join-Path $res "mipmap-$($entry[0])"
    New-Item -ItemType Directory -Force $dir | Out-Null
    $bitmap = New-Object Drawing.Bitmap $size, $size
    $g = [Drawing.Graphics]::FromImage($bitmap)
    $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([Drawing.Color]::FromArgb(16,23,32))
    $brush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(72,191,222))
    $g.FillEllipse($brush, [single]($size*.1), [single]($size*.1), [single]($size*.8), [single]($size*.8))
    $g.FillEllipse([Drawing.Brushes]::White, [single]($size*.45), [single]($size*.25), [single]($size*.1), [single]($size*.1))
    $g.FillRectangle([Drawing.Brushes]::White, [single]($size*.45), [single]($size*.42), [single]($size*.1), [single]($size*.32))
    $bitmap.Save((Join-Path $dir 'app_icon.png'), [Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bitmap.Dispose(); $brush.Dispose()
}
$dir = Join-Path $res 'drawable-xhdpi'
New-Item -ItemType Directory -Force $dir | Out-Null
$bitmap = New-Object Drawing.Bitmap 320, 180
$g = [Drawing.Graphics]::FromImage($bitmap)
$g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([Drawing.Color]::FromArgb(16,23,32))
$icon = [Drawing.Image]::FromFile((Join-Path $res 'mipmap-xxxhdpi\app_icon.png'))
$g.DrawImage($icon, 12, 40, 96, 96)
$font = New-Object Drawing.Font 'Arial', 17, ([Drawing.FontStyle]::Bold)
$small = New-Object Drawing.Font 'Arial', 10
$g.DrawString('DEVICE INFO', $font, [Drawing.Brushes]::White, 110, 64)
$g.DrawString('Android diagnostics', $small, [Drawing.Brushes]::LightGray, 112, 98)
$bitmap.Save((Join-Path $dir 'app_banner.png'), [Drawing.Imaging.ImageFormat]::Png)
$icon.Dispose(); $font.Dispose(); $small.Dispose(); $g.Dispose(); $bitmap.Dispose()
