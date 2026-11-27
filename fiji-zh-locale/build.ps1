<#
.SYNOPSIS
    构建 Fiji 中文汉化插件（Windows / PowerShell）。

.DESCRIPTION
    编译 src/Zh_Locale.java 并打包为 dist/plugins/Zh_Locale.jar，
    同时把所有运行时文件整理到 dist/（与 Fiji 安装目录同构，直接复制即可）。

    javac 优先使用 Fiji 自带的 JDK；若没有，则使用 PATH 中的 javac。
    编译依赖 ImageJ1 的 ij.jar：优先取 Fiji 的 jars 目录，找不到时自动从 Maven 中央仓库下载。

.PARAMETER FijiHome
    Fiji 安装目录（含 jars/ 与 java/），默认取本仓库上一级的 Fiji.app。

.PARAMETER OutDir
    输出目录，默认 dist。

.EXAMPLE
    .\build.ps1
    .\build.ps1 -FijiHome "D:\Fiji.app"
#>
param(
    [string]$FijiHome = (Join-Path (Split-Path -Parent $PSScriptRoot) 'Fiji.app'),
    [string]$OutDir = (Join-Path $PSScriptRoot 'dist')
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$buildDir = Join-Path $root 'build'

Write-Host "==> Fiji 目录: $FijiHome" -ForegroundColor Cyan

# ---------- 1. 定位 javac / jar ----------
$javac = $null
if (Test-Path (Join-Path $FijiHome 'java')) {
    $javac = Get-ChildItem (Join-Path $FijiHome 'java') -Recurse -Filter 'javac.exe' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $javac) {
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) { $javac = $cmd.Source }
}
if (-not $javac) {
    throw "找不到 javac。请安装 JDK 8+，或用 -FijiHome 指定内含 JDK 的 Fiji 目录。"
}
$jar = Join-Path (Split-Path $javac) 'jar.exe'
Write-Host "==> javac: $javac" -ForegroundColor Cyan

# ---------- 2. 准备 ImageJ1 依赖 ----------
$ijJar = $null
$fijiJars = Join-Path $FijiHome 'jars'
if (Test-Path $fijiJars) {
    $ijJar = Get-ChildItem $fijiJars -Filter 'ij-*.jar' | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ijJar) {
    $libDir = Join-Path $root 'lib'
    New-Item -ItemType Directory -Force -Path $libDir | Out-Null
    $ijJar = Join-Path $libDir 'ij-1.54p.jar'
    if (-not (Test-Path $ijJar)) {
        $url = 'https://repo1.maven.org/maven2/net/imagej/ij/1.54p/ij-1.54p.jar'
        Write-Host "==> 下载 ImageJ1 依赖: $url" -ForegroundColor Cyan
        Invoke-WebRequest -Uri $url -OutFile $ijJar -UseBasicParsing
    }
}
Write-Host "==> ij.jar: $ijJar" -ForegroundColor Cyan

# ---------- 3. 编译 ----------
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

& $javac -encoding UTF-8 -cp $ijJar -d $buildDir (Join-Path $root 'src\Zh_Locale.java')
if ($LASTEXITCODE -ne 0) { throw "编译失败 (javac exit $LASTEXITCODE)" }

$classes = Get-ChildItem $buildDir -Filter '*.class' | Select-Object -ExpandProperty Name
if (-not ($classes -contains 'Zh_Locale.class')) { throw "编译产物缺少 Zh_Locale.class" }
if (-not ($classes | Where-Object { $_ -like 'Zh_Locale$*.class' })) {
    throw "编译产物缺少匿名内部类（Zh_Locale`$1.class 等）。打包时会因此导致 NoClassDefFoundError。"
}
Write-Host "==> 编译完成，类文件: $($classes -join ', ')" -ForegroundColor Green

# ---------- 4. 打包并整理 dist ----------
New-Item -ItemType Directory -Force -Path (Join-Path $OutDir 'plugins') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutDir 'macros\AutoRun') | Out-Null
$outJar = Join-Path $OutDir 'plugins\Zh_Locale.jar'
if (Test-Path $outJar) { Remove-Item $outJar -Force }
Push-Location $buildDir
try {
    & $jar cf $outJar $classes
    if ($LASTEXITCODE -ne 0) { throw "打包失败 (jar exit $LASTEXITCODE)" }
} finally { Pop-Location }

Copy-Item (Join-Path $root 'dict\zh_dict.txt') (Join-Path $OutDir 'plugins\zh_dict.txt') -Force
Copy-Item (Join-Path $root 'macros\ChineseLocalization.ijm') (Join-Path $OutDir 'macros\AutoRun\ChineseLocalization.ijm') -Force

Write-Host ""
Write-Host "==> 构建完成: $outJar" -ForegroundColor Green
Write-Host "    把 dist\ 下的内容按相同结构复制到 Fiji 安装目录即可：" -ForegroundColor Yellow
Write-Host "      dist\plugins\Zh_Locale.jar                  -> Fiji.app/plugins/" -ForegroundColor Gray
Write-Host "      dist\plugins\zh_dict.txt                    -> Fiji.app/plugins/" -ForegroundColor Gray
Write-Host "      dist\macros\AutoRun\ChineseLocalization.ijm -> Fiji.app/macros/AutoRun" -ForegroundColor Gray
