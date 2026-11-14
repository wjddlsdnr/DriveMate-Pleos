$ErrorActionPreference = 'Stop'
# Match server import order. Nested imports must not remove the public dispatcher.
Import-Module (Join-Path $PSScriptRoot 'LocalRelay.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'PlacesApi.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'NearbyRelay.psm1') -Force
$health = Invoke-LocalRelayRequest -Method GET -Path /health -Token fixture -Authorization 'Bearer fixture'
if ($health.Code -ne 200 -or $health.Body.status -cne 'Ready') { throw 'Health dispatch failed.' }
$nearby = Invoke-NearbyRelayRequest -Method POST -Token fixture -Authorization 'Bearer fixture'
if ($nearby.Code -ne 403) { throw 'Nearby dispatch failed.' }
Write-Output 'Relay module composition: 2 checks passed; no provider requests.'
$localDirectory = $PSScriptRoot
function Invoke-FixtureSearch {
    param([string]$ConfigPath, [hashtable]$Nearby)
    if (-not $ConfigPath -or $Nearby.Latitude -ne 37 -or $Nearby.Longitude -ne 127) { throw 'Fixture binding failed.' }
    [pscustomobject]@{ Status='PageValidated'; Records=@() }
}
$nearby = Invoke-NearbyRelayRequest -Method POST -Token fixture -Authorization 'Bearer fixture' -Enabled `
    -Consent current-location-to-tmap -Body '{"latitude":37,"longitude":127,"radiusKm":5}' -Provider {
        param($latitude,$longitude)
        Invoke-FixtureSearch -ConfigPath (Join-Path $localDirectory 'fixture.json') -Nearby @{Latitude=$latitude;Longitude=$longitude}
    }
if ($nearby.Code -ne 200) { throw 'Nested provider binding failed.' }
Write-Output 'Nested provider binding passed; no API requests.'
