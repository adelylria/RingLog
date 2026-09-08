[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('x64', 'x86')]
    [string]$Architecture,

    [Parameter(Mandatory = $true)]
    [string]$RuntimeZip,

    [Parameter(Mandatory = $true)]
    [string]$IsccPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compiler = (Resolve-Path -LiteralPath $IsccPath).Path
$runtime = (Resolve-Path -LiteralPath $RuntimeZip).Path

$lock = ConvertFrom-StringData (Get-Content -LiteralPath `
    (Join-Path $projectRoot 'distribution\runtime-lock.properties') -Raw)
$expectedInnoVersion = $lock['inno.version']
$installedVersions = Get-ChildItem -LiteralPath (Split-Path $compiler) -Filter 'unins*.exe' `
    | ForEach-Object { ([string]$_.VersionInfo.ProductVersion).Trim() }
if ($expectedInnoVersion -ne '6.7.3' -or $installedVersions -notcontains $expectedInnoVersion) {
    throw "ISCC.exe must belong to Inno Setup 6.7.3; installed versions: '$($installedVersions -join ', ')'."
}

[xml]$pom = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml')
$version = $pom.project.version.Trim()
if ($version -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
    throw "An installer requires a release Maven version; found '$version'."
}

Push-Location $projectRoot
try {
    & java (Join-Path $projectRoot 'scripts\PrepareWindowsDistribution.java') $Architecture $runtime
    if ($LASTEXITCODE -ne 0) {
        throw "PrepareWindowsDistribution failed with exit code $LASTEXITCODE."
    }

    $stage = Join-Path $projectRoot "target\windows-installer\$Architecture"
    $output = Join-Path $projectRoot "target\windows-installer\output\$Architecture"
    $icon = Join-Path $stage 'RingLog.ico'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    & $compiler "/DRingLogVersion=$version" "/DRingLogArchitecture=$Architecture" `
        "/DRingLogStageDir=$stage" "/DRingLogOutputDir=$output" "/DRingLogIconFile=$icon" `
        (Join-Path $projectRoot 'distribution\inno\RingLog.iss')
    if ($LASTEXITCODE -ne 0) {
        throw "Inno Setup failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
