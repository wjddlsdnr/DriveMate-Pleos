$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PlacesApi.psm1') -Force
$count=0
function Check($value) { if (-not $value) { throw 'Nearby request assertion failed (values omitted).' }; $script:count++ }
$uri = New-TmapNearbyRequestUri -Latitude 37.0 -Longitude 127.0
Check ($uri.Scheme -ceq 'https')
Check ($uri.Host -ceq 'apis.openapi.sk.com')
Check ($uri.AbsolutePath -ceq '/tmap/pois')
Check ($uri.Query.Contains('searchtypCd=R'))
Check ($uri.Query.Contains('radius=5'))
Check ($uri.Query.Contains('centerLat=37'))
Check ($uri.Query.Contains('centerLon=127'))
Check (-not $uri.Query.Contains('appKey'))
foreach ($pair in @(@([double]::NaN,127),@(37,[double]::PositiveInfinity),@(91,127),@(37,181))) {
    $rejected=$false
    try { $null=New-TmapNearbyRequestUri -Latitude $pair[0] -Longitude $pair[1] } catch { $rejected=$true }
    Check $rejected
}
$original=[Threading.Thread]::CurrentThread.CurrentCulture
try {
    [Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo('fr-FR')
    $uri=New-TmapNearbyRequestUri -Latitude 37.25 -Longitude 127.5
    Check ($uri.Query.Contains('centerLat=37.25'))
    Check ($uri.Query.Contains('centerLon=127.5'))
} finally { [Threading.Thread]::CurrentThread.CurrentCulture=$original }
Write-Output "PASS: $count synthetic nearby request checks; no network requests."
