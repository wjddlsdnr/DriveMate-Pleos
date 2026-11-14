$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'LocalRelay.psm1') -Force
$count = 0
function Check($Expected, $Actual) { if ($Expected -cne $Actual) { throw 'Relay assertion failed (values omitted).' }; $script:count++ }
function Request($Method='GET', $Path='/v1/discovery', $Query='', $Auth='Bearer fixture', [switch]$Live, [scriptblock]$Provider={throw 'must not run'}) {
    Invoke-LocalRelayRequest -Method $Method -Path $Path -Query $Query -Authorization $Auth -Token 'fixture' -EnableProviderRequests:$Live -Provider $Provider
}
Check 401 (Request -Auth '').Code
Check 405 (Request -Method 'POST').Code
Check 400 (Request -Query '?location=fixture').Code
Check 404 (Request -Path '/unknown').Code
Check 'Ready' (Request -Path '/health').Body.status
Check 'ProviderDisabled' (Request).Body.status
Check 502 (Request -Live).Code
Check 502 (Request -Live -Provider { @{Status='InvalidApiKey';secret='fixture-secret'} }).Code
$provider = { @{Status='PageValidated';Records=@(@{Provider='TMAP';SourceId='fixture-id';Name='fixture';Latitude=37.0;Longitude=127.0;CoordinateSystem='WGS84GEO';DirectionVerified=$true;AccessVerified=$true;ParkingVerified=$true;secret='fixture-secret'})} }
$result = Request -Live -Provider $provider
Check 200 $result.Code
Check 'DiscoveryOnly' $result.Body.status
Check 0 $result.Body.safeCandidates.Count
Check 1 $result.Body.discoveries.Count
Check $false $result.Body.discoveries[0].directionVerified
Check $false $result.Body.discoveries[0].accessVerified
Check $false $result.Body.discoveries[0].parkingVerified
Check $false (($result.Body | ConvertTo-Json -Depth 6) -match 'fixture-secret')
Check 502 (Request -Live -Provider { @{Status='PageValidated';Records=@(@{Provider='TMAP';SourceId='bad';Name='bad';Latitude=[double]::NaN;Longitude=0;CoordinateSystem='WGS84GEO'})} }).Code
Check 502 (Request -Live -Provider { @{Status='PageValidated';Records=@(@{Provider='TMAP';SourceId='bad';Name="bad`n";Latitude=0;Longitude=0;CoordinateSystem='WGS84GEO'})} }).Code
Check $false ((Request -Live -Provider { throw 'fixture-secret' }).Body | ConvertTo-Json -Depth 6).Contains('fixture-secret')
Write-Output "PASS: $count offline relay checks; no API requests."
