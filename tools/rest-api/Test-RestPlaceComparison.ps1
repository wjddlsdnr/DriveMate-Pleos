$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'RestPlaceComparison.psm1') -Force
$count=0
function Assert-Equal($Expected,$Actual) {
    if ($Expected -cne $Actual) { throw 'Comparison assertion failed (values omitted).' }
    $script:count++
}
function Record($Provider,$Id,$Name) { [pscustomobject]@{Provider=$Provider;SourceId=$Id;Name=$Name} }
Assert-Equal '테스트|부산' (Get-RestPlaceNameKey '테스트 (부산방향)')
Assert-Equal '테스트|부산' (Get-RestPlaceNameKey '테스트휴게소 부산방향')
Assert-Equal '테스트|서울' (Get-RestPlaceNameKey '테스트휴게소（서울방향）')
Assert-Equal $null (Get-RestPlaceNameKey '테스트휴게소')
Assert-Equal $null (Get-RestPlaceNameKey '테스트휴게소 부산방향 주유소')
Assert-Equal $null (Get-RestPlaceNameKey "테스트`n(부산)")
$ex=[pscustomobject]@{Status='Complete';Records=@((Record 'EX' 'ex-a' '테스트(부산)'),(Record 'EX' 'ex-b' '테스트(서울)'))}
$tmap=[pscustomobject]@{Status='PageValidated';Records=@((Record 'TMAP' 'tmap-a' '테스트휴게소 부산방향'))}
$result=Compare-RestPlaceCatalogs $ex $tmap
Assert-Equal 1 @($result.Comparisons | Where-Object Status -eq 'NameDirectionAgreement').Count
Assert-Equal 1 @($result.Comparisons | Where-Object Status -eq 'NotInFetchedPage').Count
$agreement=@($result.Comparisons | Where-Object Status -eq 'NameDirectionAgreement')[0]
Assert-Equal 'ex-a' $agreement.ExId
Assert-Equal 'tmap-a' $agreement.TmapId
Assert-Equal $false $agreement.IdentityVerified
Assert-Equal $false $agreement.AccessVerified
Assert-Equal $null $agreement.CoordinateAgreement
Assert-Equal 0 $result.SafeCandidates.Count
$tmap.Records += Record 'TMAP' 'tmap-b' '테스트(부산)'
Assert-Equal 1 @((Compare-RestPlaceCatalogs $ex $tmap).Comparisons | Where-Object Status -eq 'Ambiguous').Count
$tmap.Records=@((Record 'TMAP' 'tmap-a' '테스트(하행)'))
Assert-Equal 0 @((Compare-RestPlaceCatalogs $ex $tmap).Comparisons | Where-Object Status -eq 'NameDirectionAgreement').Count
$tmap.Records=@((Record 'TMAP' 'tmap-a' '테스트(부산)'),(Record 'TMAP' 'tmap-a' '테스트(부산)'))
Assert-Equal 1 @((Compare-RestPlaceCatalogs $ex $tmap).Comparisons | Where-Object Status -eq 'Ambiguous').Count
$ex.Status='Incomplete'
Assert-Equal 'InputNotComplete' (Compare-RestPlaceCatalogs $ex $tmap).Status
Write-Output "PASS: $count comparison checks; no API requests."
