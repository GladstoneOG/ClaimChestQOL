$ErrorActionPreference = "Stop"

$ServerJar = "F:\serverht\Server\HytaleServer.jar"
$ModsDir = "F:\serverht\Server\mods"
$BuildDir = "f:\PROJECT\hytalemod\build"
$ClassesDir = "$BuildDir\classes"
$DistDir = "$BuildDir\dist"
$JarName = "ClaimChestQOL-1.0.0.jar"
$OldJarName = "PlotCraftQoL-1.0.0.jar"

if (Test-Path $BuildDir) {
    cmd.exe /c "rd /s /q `"$BuildDir`""
}
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

$JavaSources = Get-ChildItem -Path "f:\PROJECT\hytalemod\src\main\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

Write-Host "Compiling $($JavaSources.Count) Java source files against $ServerJar..."
& javac --release 25 -cp $ServerJar -d $ClassesDir $JavaSources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed with exit code $LASTEXITCODE"
}

Copy-Item "f:\PROJECT\hytalemod\src\main\resources\manifest.json" -Destination "$ClassesDir\manifest.json"

$OutputJar = "$DistDir\$JarName"
Write-Host "Packaging $OutputJar..."
& jar cf $OutputJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar packaging failed with exit code $LASTEXITCODE"
}

if (Test-Path $ModsDir) {
    $OldJarPath = "$ModsDir\$OldJarName"
    if (Test-Path $OldJarPath) {
        Remove-Item $OldJarPath -Force
        Write-Host "Removed old mod jar: $OldJarPath"
    }
    Copy-Item $OutputJar -Destination "$ModsDir\$JarName" -Force
    Write-Host "Deployed $JarName to $ModsDir\$JarName"
}
