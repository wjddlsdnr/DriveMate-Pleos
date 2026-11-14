$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'NearbyRelay.psm1') -Force
$count=0
function Check($expected,$actual) { if($expected -cne $actual){throw 'Nearby relay assertion failed (values omitted).'}; $script:count++ }
function Request([string]$Body='{"latitude":37,"longitude":127,"radiusKm":5}',[string]$Auth='Bearer fixture',
    [string]$Consent='current-location-to-tmap',[string]$Method='POST',[string]$Query='', [bool]$Enabled=$true,
    [scriptblock]$Provider={param($latitude,$longitude); if($latitude -ne 37 -or $longitude -ne 127){throw 'Bad fixture'}; @{Status='PageValidated';Records=@()}}) {
    Invoke-NearbyRelayRequest -Method $Method -Query $Query -Authorization $Auth -Token fixture -Consent $Consent -Body $Body -Enabled:$Enabled -Provider $Provider
}
Check 401 (Request -Auth '').Code
Check 405 (Request -Method GET).Code
Check 403 (Request -Enabled $false).Code
Check 403 (Request -Consent '').Code
Check 400 (Request -Query '?location=fixture').Code
foreach($body in @('','[]','{}','{"latitude":37,"longitude":127,"radiusKm":6}',
    '{"latitude":91,"longitude":127,"radiusKm":5}', '{"latitude":37,"longitude":181,"radiusKm":5}',
    '{"latitude":"37","longitude":127,"radiusKm":5}',
    '{"latitude":37,"longitude":127,"radiusKm":5,"profile":"fixture"}',
    '{"latitude":37,"latitude":38,"radiusKm":5}', (' ' * 1025))) { Check 400 (Request -Body $body).Code }
$result=Request
Check 200 $result.Code
Check 'DiscoveryOnly' $result.Body.status
Check 0 $result.Body.safeCandidates.Count
Check 502 (Request -Provider {throw 'fixture-private'}).Code
Write-Output "PASS: $count synthetic nearby relay checks; no API requests."
