param([string]$Keyword='휴게소', [switch]$FirstNamedRestStop)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PlacesApi.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'RestPlaceComparison.psm1') -Force
$path = Join-Path $PSScriptRoot '../../.local/rest-api-keys.json'
try {
    $ex = Invoke-ExRestCatalog -ConfigPath $path
    if ($ex.Status -ne 'Complete') { Write-Output ('EX: ' + $ex.Status); exit 1 }
    if ($FirstNamedRestStop) {
        $keys = @($ex.Records | ForEach-Object { Get-RestPlaceNameKey -Name $_.Name } | Where-Object { $null -ne $_ })
        if ($keys.Count -eq 0) { Write-Output 'No explicit name/direction found; no TMAP request made.'; exit 1 }
        $Keyword = $keys[0].Split('|')[0] + '휴게소'
    }
    $tmap = Invoke-TmapPlaceSearch -ConfigPath $path -Keyword $Keyword
    if ($tmap.Status -ne 'PageValidated') { Write-Output ('TMAP: ' + $tmap.Status); exit 1 }
    $result = Compare-RestPlaceCatalogs -ExCatalog $ex -TmapPage $tmap
    [pscustomobject]@{
        Status=$result.Status; ExRecords=$ex.Records.Count; TmapPageRecords=$tmap.Records.Count
        NameDirectionAgreements=@($result.Comparisons | Where-Object Status -eq 'NameDirectionAgreement').Count
        Ambiguous=@($result.Comparisons | Where-Object Status -eq 'Ambiguous').Count
        SafeCandidates=$result.SafeCandidates.Count
    } | Format-List
    Write-Output 'One TMAP page only. Unmatched does not mean absent. No route changes.'
} catch { Write-Output 'Comparison failed; sensitive details suppressed.'; exit 1 }
