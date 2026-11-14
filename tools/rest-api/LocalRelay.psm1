Set-StrictMode -Version Latest

function Invoke-LocalRelayRequest {
    param([string]$Method, [string]$Path, [string]$Query, [string]$Authorization,
        [Parameter(Mandatory)][string]$Token, [switch]$EnableProviderRequests, [scriptblock]$Provider)
    # Compare before invoking any provider. Never return exception text or arbitrary provider objects.
    $code = 200
    $body = @{ version = 1; status = 'Unsupported'; safeCandidates = @(); discoveries = @() }
    if ($Authorization -cne "Bearer $Token") { $code = 401; $body.status = 'Unauthorized' }
    elseif ($Method -cne 'GET') { $code = 405; $body.status = 'MethodNotAllowed' }
    elseif ($Query) { $code = 400; $body.status = 'QueryNotAllowed' }
    elseif ($Path -ceq '/health') { $body.status = 'Ready'; $body.providerEnabled = [bool]$EnableProviderRequests }
    elseif ($Path -cne '/v1/discovery') { $code = 404; $body.status = 'NotFound' }
    elseif (-not $EnableProviderRequests) { $body.status = 'ProviderDisabled' }
    else {
        try {
            $result = & $Provider
            if ($result.Status -cne 'PageValidated') { $code = 502; $body.status = 'ProviderUnavailable' }
            else {
                $items = @($result.Records)
                if ($items.Count -gt 20) { throw 'Invalid provider data.' }
                $projected = @($items | ForEach-Object {
                    if ($_.Provider -cne 'TMAP' -or $_.CoordinateSystem -cne 'WGS84GEO' -or
                        $_.SourceId -isnot [string] -or $_.SourceId.Length -notin 1..80 -or
                        $_.Name -isnot [string] -or $_.Name.Length -notin 1..100 -or
                        $_.SourceId -match '[\p{Cc}\p{Cf}]' -or $_.Name -match '[\p{Cc}\p{Cf}]') { throw 'Invalid data.' }
                    $lat = [double]$_.Latitude; $lon = [double]$_.Longitude
                    if ([double]::IsNaN($lat) -or [double]::IsNaN($lon) -or
                        $lat -lt -90 -or $lat -gt 90 -or $lon -lt -180 -or $lon -gt 180) { throw 'Invalid coordinate.' }
                    @{ provider = 'TMAP'; sourceId = $_.SourceId; name = $_.Name;
                        latitude = $lat; longitude = $lon; coordinateSystem = 'WGS84GEO';
                        directionVerified = $false; accessVerified = $false; parkingVerified = $false }
                })
                $body.status = 'DiscoveryOnly'; $body.discoveries = $projected
            }
        } catch { $code = 502; $body.status = 'ProviderUnavailable'; $body.discoveries = @() }
    }
    [pscustomobject]@{ Code = $code; Body = $body }
}
Export-ModuleMember -Function Invoke-LocalRelayRequest
