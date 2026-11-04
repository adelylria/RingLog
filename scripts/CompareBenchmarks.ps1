[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Before,
    [Parameter(Mandatory = $true)][string]$After,
    [string]$OutputFile
)
$ErrorActionPreference = 'Stop'
$beforeReport = Get-Content -LiteralPath $Before -Raw -Encoding UTF8 | ConvertFrom-Json
$afterReport = Get-Content -LiteralPath $After -Raw -Encoding UTF8 | ConvertFrom-Json

function Get-RecordedField($Target, [string]$Name) {
    if ($null -eq $Target) { return $null }
    $property = $Target.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-CaseEntries($Report) {
    $workers = if ($Report.reports) { @($Report.reports) } else { @($Report) }
    foreach ($worker in $workers) {
        foreach ($case in @($worker.cases)) {
            if ($null -eq $case) { continue }
            [pscustomobject]@{
                Worker = $worker
                Case = $case
                Key = '{0}|{1}|{2}|{3}' -f $worker.group, $case.group, $case.name, $case.rows
            }
        }
    }
}

function Get-FieldDifferences($BeforeTarget, $AfterTarget, [string[]]$Fields) {
    foreach ($field in $Fields) {
        $beforeValue = Get-RecordedField $BeforeTarget $field
        $afterValue = Get-RecordedField $AfterTarget $field
        if ($null -eq $beforeValue -or $null -eq $afterValue) {
            # Two absent values are unknown, rather than evidence of an identical environment.
            "$field (sin datos)"
        } elseif ([string]$beforeValue -cne [string]$afterValue) {
            "$field ($beforeValue -> $afterValue)"
        }
    }
}

$beforeEntries = @(Get-CaseEntries $beforeReport)
$afterEntries = @(Get-CaseEntries $afterReport)
$environmentFields = @('os.name', 'os.version', 'os.arch', 'java.version', 'java.vendor',
    'java.vm.name', 'processors', 'maxHeapMiB', 'headless', 'gc')
$workerWorkloadFields = @('profile', 'schema', 'historyLength', 'reportLimit')
$comparison = @()
foreach ($newEntry in $afterEntries) {
    $newCase = $newEntry.Case
    $oldEntries = @($beforeEntries | Where-Object { $_.Key -eq $newEntry.Key })
    if ($oldEntries.Count -ne 1) {
        Write-Warning "No hay una base unica para $($newEntry.Worker.group)/$($newCase.name), registros=$($newCase.rows); caso omitido."
        continue
    }
    $oldEntry = $oldEntries[0]
    $oldCase = $oldEntry.Case
    $environmentDifferences = @(Get-FieldDifferences $oldEntry.Worker.environment $newEntry.Worker.environment $environmentFields)
    $workloadDifferences = @(Get-FieldDifferences $oldEntry.Worker $newEntry.Worker $workerWorkloadFields)
    $workloadDifferences += @(Get-FieldDifferences $oldCase $newCase @('warmups', 'samples'))
    $sameEnvironment = $environmentDifferences.Count -eq 0
    $sameWorkload = $workloadDifferences.Count -eq 0
    $successful = $oldCase.status -eq 'PASS' -and $newCase.status -eq 'PASS'
    $validMedians = $null -ne $oldCase.medianMs -and $null -ne $newCase.medianMs -and $oldCase.medianMs -gt 0
    $change = if ($successful -and $validMedians) {
        100 * ([double]$newCase.medianMs / [double]$oldCase.medianMs - 1)
    } else { $null }
    $comparison += [pscustomobject]@{
        WorkerGroup = $newEntry.Worker.group
        Group = $newCase.group; Operation = $newCase.name; Rows = $newCase.rows
        BeforeStatus = $oldCase.status; AfterStatus = $newCase.status
        BeforeMs = $oldCase.medianMs; AfterMs = $newCase.medianMs
        ChangePercent = $change
        SameEnvironment = $sameEnvironment
        SameWorkload = $sameWorkload
        Comparable = $sameEnvironment -and $sameWorkload -and $successful -and $validMedians
        EnvironmentDifferences = $environmentDifferences -join '; '
        WorkloadDifferences = $workloadDifferences -join '; '
    }
}
if (@($comparison | Where-Object { -not $_.SameEnvironment }).Count -gt 0) {
    Write-Warning 'Algunos casos tienen caracteristicas de entorno distintas o incompletas; no atribuyas esas diferencias al codigo.'
}
if (@($comparison | Where-Object { -not $_.SameWorkload }).Count -gt 0) {
    Write-Warning 'Algunos casos cambian perfil, schema, historial, limite de informes, calentamientos o muestras; sus tiempos no representan la misma carga.'
}
if (@($comparison | Where-Object { $_.BeforeStatus -ne 'PASS' -or $_.AfterStatus -ne 'PASS' }).Count -gt 0) {
    Write-Warning 'Hay casos fallidos u omitidos: no se calcula una mejora porcentual para ellos.'
}
if ($comparison.Count -eq 0) { Write-Warning 'No hay casos coincidentes que comparar.' }
$comparison | Sort-Object ChangePercent | Format-Table WorkerGroup, Group, Operation, Rows, BeforeMs, AfterMs,
    ChangePercent, SameEnvironment, SameWorkload, Comparable -AutoSize
if ($OutputFile) { $comparison | Export-Csv -LiteralPath $OutputFile -NoTypeInformation -Encoding UTF8 }
