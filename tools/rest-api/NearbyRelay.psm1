Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'LocalRelay.psm1')
function Invoke-NearbyRelayRequest {
    param([string]$Method, [string]$Query, [string]$Authorization, [string]$Token,
        [string]$Consent, [string]$Body, [switch]$Enabled, [scriptblock]$Provider)
    $failure=@{version=1;status='InvalidRequest';discoveries=@();safeCandidates=@()}; $code=400
    if ($Authorization -cne "Bearer $Token") { $code=401; $failure.status='Unauthorized' }
    elseif ($Method -cne 'POST') { $code=405; $failure.status='MethodNotAllowed' }
    elseif (-not $Enabled) { $code=403; $failure.status='LocationRequestsDisabled' }
    elseif ($Consent -cne 'current-location-to-tmap') { $code=403; $failure.status='ConsentRequired' }
    elseif (-not $Query -and [Text.Encoding]::UTF8.GetByteCount($Body) -le 1024) {
        $document=$null
        try {
            $document=[System.Text.Json.JsonDocument]::Parse($Body); $root=$document.RootElement
            if ($root.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) { throw 'Invalid object.' }
            $properties=@($root.EnumerateObject())
            if ($properties.Count -ne 3 -or @($properties.Name | Sort-Object -Unique).Count -ne 3 -or
                @($properties.Name | Where-Object { $_ -cnotin @('latitude','longitude','radiusKm') }).Count -ne 0) { throw 'Invalid schema.' }
            $lat=$root.GetProperty('latitude').GetDouble(); $lon=$root.GetProperty('longitude').GetDouble()
            if ($root.GetProperty('radiusKm').GetInt32() -ne 5 -or
                -not [double]::IsFinite($lat) -or -not [double]::IsFinite($lon) -or
                $lat -lt -90 -or $lat -gt 90 -or $lon -lt -180 -or $lon -gt 180) { throw 'Invalid position.' }
            # Header declares scope; authenticated client must obtain actual UI consent before dispatch.
            $upstream=$Provider
            $nearbyCall={ & $upstream $lat $lon }.GetNewClosure()
            return Invoke-LocalRelayRequest -Method GET -Path '/v1/discovery' -Query '' `
                -Authorization $Authorization -Token $Token -EnableProviderRequests -Provider $nearbyCall
        } catch { }
        finally { if ($null -ne $document) { $document.Dispose() } }
    }
    [pscustomobject]@{Code=$code;Body=$failure}
}
Export-ModuleMember -Function Invoke-NearbyRelayRequest
