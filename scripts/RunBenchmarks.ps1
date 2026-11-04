[CmdletBinding()]
param(
    [ValidateSet('quick', 'full')][string]$Profile = 'quick',
    [int[]]$Rows = @(),
    [ValidateRange(0, 20)][int]$Warmups = 2,
    [ValidateRange(1, 50)][int]$Samples = 5,
    [ValidateRange(1, 5000)][int]$ReportLimit = 250,
    [ValidateRange(1, 1000)][int]$HistoryLength = 10,
    [ValidateRange(128, 2048)][int]$HeapMiB = 512,
    [ValidateRange(60, 14400)][int]$TimeoutSeconds = 1200,
    [ValidateSet('db', 'ui', 'storage', 'portable', 'desktop')][string[]]$Groups = @('db', 'ui', 'storage'),
    [string]$OutputDirectory,
    [string]$JavaPath,
    [switch]$SkipBuild,
    [switch]$IncludePortable,
    [switch]$IncludeDesktop,
    [switch]$CreatePortableKit
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$packaged = Test-Path -LiteralPath (Join-Path $PSScriptRoot 'RingLog-Benchmarks.jar')
if ($packaged) { $projectRoot = $PSScriptRoot; $SkipBuild = $true }
if ($IncludePortable -and $Groups -notcontains 'portable') { $Groups += 'portable' }
if ($IncludeDesktop -and $Groups -notcontains 'desktop') { $Groups += 'desktop' }
Set-Location -LiteralPath $projectRoot
if ($Rows.Count -eq 0) { $Rows = if ($Profile -eq 'full') { @(1000, 10000, 50000) } else { @(1000, 10000) } }
foreach ($size in $Rows) { if ($size -lt 100 -or $size -gt 100000) { throw 'Rows must be between 100 and 100000.' } }

if (-not $JavaPath) {
    $bundledJdk = Join-Path $projectRoot '.deps/jdk17/zulu17.42.19-ca-jdk17.0.7-win_x64/bin/java.exe'
    $installedJava = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Programs/RingLog/runtime/bin/java.exe' } else { '' }
    if (Test-Path -LiteralPath $bundledJdk) { $JavaPath = $bundledJdk }
    elseif ($installedJava -and (Test-Path -LiteralPath $installedJava)) { $JavaPath = $installedJava }
    elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $JavaPath = Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else {
        $command = Get-Command java.exe -ErrorAction SilentlyContinue
        if (-not $command) { throw 'No se encuentra Java 17. Indica -JavaPath con el Java incluido en RingLog.' }
        $JavaPath = $command.Source
    }
}
$JavaPath = (Resolve-Path -LiteralPath $JavaPath).Path

if (-not $SkipBuild) {
    $jdk = Split-Path (Split-Path $JavaPath -Parent) -Parent
    if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin/javac.exe'))) { throw 'Compilar requiere JDK 17; el paquete para el portatil usa solo el Java instalado.' }
    $env:JAVA_HOME = $jdk
    $env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
    $env:MAVEN_SKIP_RC = 'true'
    $maven = Join-Path $projectRoot '.deps/apache-maven-3.9.11/bin/mvn.cmd'
    if (-not (Test-Path -LiteralPath $maven)) {
        $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if (-not $mavenCommand) { throw 'Se necesita Maven 3.6.3 o posterior para preparar el paquete.' }
        $maven = $mavenCommand.Source
    }
    & $maven -o '-DskipTests' '-Dexec.skip=true' package
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar el benchmark.' }
}

$kitRoot = if ($packaged) { $projectRoot } else { Join-Path $projectRoot 'target/benchmark-tools' }
$jar = Join-Path $kitRoot 'RingLog-Benchmarks.jar'
if (-not $packaged) {
    New-Item -ItemType Directory -Force -Path $kitRoot | Out-Null
    $jarTool = Join-Path (Split-Path $JavaPath -Parent) 'jar.exe'
    if (-not (Test-Path -LiteralPath $jarTool)) { throw 'Preparar el paquete requiere jar.exe del JDK 17.' }
    & $jarTool --create --file $jar --main-class com.adelylria.ringlog.benchmark.RingLogBenchmark `
        -C target/classes . -C target/test-classes com/adelylria/ringlog/benchmark
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear RingLog-Benchmarks.jar.' }
    $revision = 'working-tree-unspecified'
    if (Test-Path -LiteralPath '.git') {
        $revision = (git rev-parse HEAD).Trim()
        if (@(git status --porcelain=v1).Count -gt 0) { $revision += '+dirty' }
    }
    $sourceFiles = Get-ChildItem -LiteralPath @('src/main', 'src/test/java/com/adelylria/ringlog/benchmark', 'database/schema.sql') -Recurse -File | Sort-Object FullName
    $sourceHashes = @($sourceFiles | ForEach-Object { $_.FullName.Substring($projectRoot.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }) -join "`n"
    $sourceDigest = [Security.Cryptography.SHA256]::Create()
    try { $sourceFingerprint = ([BitConverter]::ToString($sourceDigest.ComputeHash([Text.Encoding]::UTF8.GetBytes($sourceHashes)))).Replace('-', '').ToLowerInvariant() }
    finally { $sourceDigest.Dispose() }
    $revision += '-src' + $sourceFingerprint.Substring(0, 12)
    $classFiles = Get-ChildItem -LiteralPath @('target/classes', 'target/test-classes/com/adelylria/ringlog/benchmark') -Recurse -File | Sort-Object FullName
    $classHashes = @($classFiles | ForEach-Object { $_.FullName.Substring($projectRoot.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }) -join "`n"
    $classDigest = [Security.Cryptography.SHA256]::Create()
    try { $classFingerprint = ([BitConverter]::ToString($classDigest.ComputeHash([Text.Encoding]::UTF8.GetBytes($classHashes)))).Replace('-', '').ToLowerInvariant() }
    finally { $classDigest.Dispose() }
    $revision += '-classes' + $classFingerprint.Substring(0, 12)
    $dependencyFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'target/libs') -Filter '*.jar' -File | Sort-Object Name)
    $dependencyHashes = @($dependencyFiles | ForEach-Object { $_.Name + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }) -join "`n"
    $dependencyDigest = [Security.Cryptography.SHA256]::Create()
    try { $dependencyFingerprint = ([BitConverter]::ToString($dependencyDigest.ComputeHash([Text.Encoding]::UTF8.GetBytes($dependencyHashes)))).Replace('-', '').ToLowerInvariant() }
    finally { $dependencyDigest.Dispose() }
    $revision += '-libs' + $dependencyFingerprint.Substring(0, 12)
    Set-Content -LiteralPath (Join-Path $kitRoot 'revision.txt') -Value $revision -Encoding ASCII
    if ($CreatePortableKit -or $Groups -contains 'portable') {
        $libraryTarget = Join-Path $kitRoot 'libs'
        New-Item -ItemType Directory -Force -Path $libraryTarget | Out-Null
        $dependencyNames = @($dependencyFiles | ForEach-Object { $_.Name })
        Get-ChildItem -LiteralPath $libraryTarget -Filter '*.jar' -File | Where-Object { $dependencyNames -notcontains $_.Name } | ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Force
        }
        $dependencyFiles | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $libraryTarget $_.Name) -Force
        }
        $benchmarkApp = Join-Path $kitRoot 'app'
        New-Item -ItemType Directory -Force -Path $benchmarkApp | Out-Null
        $benchmarkVersion = ([xml](Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw)).project.version.Trim()
        Copy-Item -LiteralPath (Join-Path $projectRoot "target/RingLog-$benchmarkVersion.jar") -Destination (Join-Path $benchmarkApp 'RingLog.jar') -Force
        Copy-Item -LiteralPath (Join-Path $projectRoot 'distribution/assets/RingLog.ico') -Destination (Join-Path $benchmarkApp 'RingLog.ico') -Force
        $runtimeLock = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $projectRoot 'distribution/runtime-lock.properties') -Raw)
        @("version=$benchmarkVersion", 'architecture=x64', ('runtime.filename=' + $runtimeLock['x64.filename']),
            ('runtime.sha256=' + $runtimeLock['x64.sha256'])) | Set-Content -LiteralPath (Join-Path $benchmarkApp 'distribution.properties') -Encoding ASCII
    }
    if ($CreatePortableKit) {
        Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $kitRoot 'RunBenchmarks.ps1') -Force
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'CompareBenchmarks.ps1') -Destination (Join-Path $kitRoot 'CompareBenchmarks.ps1') -Force
        Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/benchmarks.md') -Destination (Join-Path $kitRoot 'LEEME.md') -Force
        $launcher = @'
@echo off
setlocal
cd /d "%~dp0"
echo RingLog - benchmark con datos sinteticos
echo No se abre su diario. El informe quedara en la carpeta results.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0RunBenchmarks.ps1" -Profile quick -Rows 1000 -SkipBuild -IncludeDesktop %*
set "BENCH_EXIT=%ERRORLEVEL%"
echo.
echo Benchmark terminado. Revise results\...\report.md.
pause
exit /b %BENCH_EXIT%
'@
        Set-Content -LiteralPath (Join-Path $kitRoot 'Ejecutar-benchmark.cmd') -Value $launcher -Encoding ASCII
        $zip = Join-Path $projectRoot 'target/RingLog-Benchmarks.zip'
        Compress-Archive -LiteralPath $jar, (Join-Path $kitRoot 'libs'), (Join-Path $kitRoot 'app'), (Join-Path $kitRoot 'RunBenchmarks.ps1'), `
            (Join-Path $kitRoot 'CompareBenchmarks.ps1'), (Join-Path $kitRoot 'LEEME.md'), `
            (Join-Path $kitRoot 'revision.txt'), (Join-Path $kitRoot 'Ejecutar-benchmark.cmd') -DestinationPath $zip -Force
        Write-Output "Paquete para el portatil: $zip"
    }
}
if (-not (Test-Path -LiteralPath $jar)) { throw 'Falta RingLog-Benchmarks.jar.' }
if (-not $OutputDirectory) {
    $base = if ($packaged) { Join-Path $projectRoot 'results' } else { Join-Path $projectRoot 'target/benchmarks' }
    $OutputDirectory = Join-Path $base (Get-Date -Format 'yyyyMMdd-HHmmss')
}
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$revisionFile = Join-Path $kitRoot 'revision.txt'
$revision = if (Test-Path -LiteralPath $revisionFile) { (Get-Content -LiteralPath $revisionFile -Raw).Trim() } else { 'unspecified' }
$libraries = if ($packaged) { Join-Path $kitRoot 'libs/*' } else { Join-Path $projectRoot 'target/libs/*' }
$classpath = $jar + ';' + $libraries
$benchmarkRuntime = if (-not $packaged -and (Test-Path -LiteralPath (Join-Path $projectRoot 'target/windows-installer/x64/runtime/release'))) {
    Join-Path $projectRoot 'target/windows-installer/x64/runtime'
} else { Split-Path (Split-Path $JavaPath -Parent) -Parent }
$reports = @()
$processes = @()
$failed = $false
foreach ($size in $Rows) {
    foreach ($group in $Groups) {
        $caseDirectory = Join-Path $output ($group + '-' + $size)
        if (Test-Path -LiteralPath (Join-Path $caseDirectory 'work/synthetic-data')) {
            throw "La salida ya contiene un fixture; elige otra carpeta: $caseDirectory"
        }
        New-Item -ItemType Directory -Force -Path $caseDirectory | Out-Null
        $stopwatch = [Diagnostics.Stopwatch]::StartNew()
        $headless = if ($group -eq 'desktop') { 'false' } else { 'true' }
        $arguments = @('-Xms64m', "-Xmx${HeapMiB}m", "-Djava.awt.headless=$headless", '-Dfile.encoding=UTF-8',
            "-Dringlog.benchmark.revision=$revision", "-Dringlog.benchmark.kitRoot=$kitRoot", "-Dringlog.benchmark.runtime=$benchmarkRuntime", '-cp', $classpath,
            'com.adelylria.ringlog.benchmark.RingLogBenchmark', '--group', $group, '--profile', $Profile,
            '--rows', [string]$size, '--warmups', [string]$Warmups, '--samples', [string]$Samples,
            '--history-length', [string]$HistoryLength, '--report-limit', [string]$ReportLimit, '--output', $caseDirectory)
        $arguments += @('--timeout-seconds', [string]$TimeoutSeconds)
        & $JavaPath @arguments 2>&1 | Tee-Object -FilePath (Join-Path $caseDirectory 'console.log')
        $workerExit = $LASTEXITCODE
        $stopwatch.Stop()
        $reportFile = Join-Path $caseDirectory 'results.json'
        if ($workerExit -eq 0 -and -not (Test-Path -LiteralPath $reportFile)) { $workerExit = 1 }
        if ($workerExit -ne 0) { $failed = $true }
        $processes += [pscustomobject]@{ Group = $group; Rows = $size; ExitCode = $workerExit; TotalMsIncludingSetup = $stopwatch.Elapsed.TotalMilliseconds }
        if (Test-Path -LiteralPath $reportFile) { $reports += Get-Content -LiteralPath $reportFile -Raw | ConvertFrom-Json }
    }
}
$cases = @($reports | ForEach-Object { $_.cases })
$aggregate = [ordered]@{ format = 'RingLog benchmark aggregate'; formatVersion = 1; generatedAt = [DateTime]::UtcNow.ToString('o');
    revision = $revision; profile = $Profile; historyLength = $HistoryLength; processes = $processes; reports = $reports; cases = $cases }
$aggregate | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $output 'results.json') -Encoding UTF8
$cases | Select-Object group, name, rows, status, warmups, samples, medianMs, p95Ms, minMs, maxMs, gcCount, gcMillis, checksum |
    Export-Csv -LiteralPath (Join-Path $output 'results.csv') -NoTypeInformation -Encoding UTF8
$lines = @('# RingLog: informe de rendimiento', '', 'Datos sinteticos; cada grupo y tamano se ejecuta en su propia JVM.',
    ('Revision: `' + $revision + '`. Perfil: ' + $Profile + '. Eventos por ave: ' + $HistoryLength + '.'),
    '', 'Los tiempos corresponden a este equipo. ui: pintura en memoria; desktop (opcional): ventana real y sync con el toolkit. No equivalen a medir el portatil Windows 8.1.',
    'p95 con pocas muestras es orientativo. GC y memoria son observaciones, no asignaciones exactas. La preparacion de datos queda fuera de los casos.', '',
    '| Grupo | Operacion | Registros | n | Mediana ms | p95 ms | Estado |', '|---|---|---:|---:|---:|---:|---|')
foreach ($case in $cases) {
    $lines += '| ' + $case.group + ' | ' + $case.name + ' | ' + $case.rows + ' | ' + $case.samples + ' | ' +
        ([double]$case.medianMs).ToString('F3', [Globalization.CultureInfo]::InvariantCulture) + ' | ' +
        ([double]$case.p95Ms).ToString('F3', [Globalization.CultureInfo]::InvariantCulture) + ' | ' + $case.status + ' |'
}
$lines += @('', '## Casos mas lentos por mediana', '')
foreach ($case in @($cases | Where-Object status -eq 'PASS' | Sort-Object medianMs -Descending | Select-Object -First 12)) {
    $lines += '- ' + $case.group + '/' + $case.name + ' (' + $case.rows + ' registros): ' +
        ([double]$case.medianMs).ToString('F2', [Globalization.CultureInfo]::InvariantCulture) + ' ms.'
}
$errors = @($cases | Where-Object status -eq 'FAILED')
if ($errors.Count -gt 0) {
    $lines += @('', '## Errores', '')
    foreach ($case in $errors) { $lines += '- ' + $case.group + '/' + $case.name + ': ' + $case.error }
}
$fatalReports = @($reports | Where-Object { $_.fatalError })
foreach ($fatal in $fatalReports) { $lines += '- Proceso ' + $fatal.group + ': ' + $fatal.fatalError }
$brokenProcesses = @($processes | Where-Object ExitCode -ne 0)
foreach ($broken in $brokenProcesses) { $lines += '- Proceso incompleto: ' + $broken.Group + ' (' + $broken.Rows + ' registros). Ver console.log.' }
$lines | Set-Content -LiteralPath (Join-Path $output 'report.md') -Encoding UTF8
Write-Output "Informe: $(Join-Path $output 'report.md')"
if ($failed -or $errors.Count -gt 0) { exit 1 }
