$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Build = Join-Path $Root "build/manual"
$Classes = Join-Path $Build "classes"
$Stage = Join-Path $Build "stage"
$Out = Join-Path $Root "build/libs"
$Core = Join-Path $Root "libs/worldmemory-core-binary.jar"

Remove-Item $Build -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Classes, $Stage, $Out | Out-Null

$Sources = Get-ChildItem (Join-Path $Root "src/main/java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $Sources) { throw "No Java sources found." }

& javac --release 21 -cp $Core -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Push-Location $Stage
& jar xf $Core
if ($LASTEXITCODE -ne 0) { throw "Could not extract binary core" }
Pop-Location

Copy-Item (Join-Path $Classes "*") $Stage -Recurse -Force
Copy-Item (Join-Path $Root "src/main/resources/*") $Stage -Recurse -Force
Remove-Item (Join-Path $Stage "META-INF") -Recurse -Force -ErrorAction SilentlyContinue

$JarPath = Join-Path $Out "WorldMemory-0.1.0-alpha.53.1-reconstructed.jar"
Push-Location $Stage
& jar cf $JarPath .
if ($LASTEXITCODE -ne 0) { throw "jar packaging failed" }
Pop-Location

Write-Host "Built: $JarPath"
