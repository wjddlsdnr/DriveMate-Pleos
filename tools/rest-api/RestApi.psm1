Set-StrictMode -Version Latest

function Get-RestApiKeyStatus {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return [pscustomobject]@{ TmapConfigured = $false; ExConfigured = $false }
    }
    try { $config = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'Key configuration is not valid JSON. Values have not been printed.' }
    $tmap = $config.PSObject.Properties['tmapAppKey']
    $ex = $config.PSObject.Properties['exApiKey']
    [pscustomobject]@{
        TmapConfigured = ($null -ne $tmap -and $tmap.Value -is [string] -and -not [string]::IsNullOrWhiteSpace($tmap.Value))
        ExConfigured = ($null -ne $ex -and $ex.Value -is [string] -and -not [string]::IsNullOrWhiteSpace($ex.Value))
    }
}

function New-ExRestRequestUri {
    param([Parameter(Mandatory)][string]$Key, [string]$RouteNo = '',
        [ValidateRange(1,1000)][int]$PageNo = 1, [ValidateRange(1,99)][int]$NumOfRows = 10)
    if ([string]::IsNullOrWhiteSpace($Key) -or $Key -match '[\r\n]') { throw 'Invalid EX key.' }
    if ($RouteNo -and $RouteNo -notmatch '^\d{1,10}$') { throw 'RouteNo must contain only digits.' }
    $uri = 'https://data.ex.co.kr/openapi/locationinfo/locationinfoRest?type=json&key=' + [uri]::EscapeDataString($Key)
    if ($RouteNo) { $uri += '&routeNo=' + [uri]::EscapeDataString($RouteNo) }
    $uri += '&pageNo=' + $PageNo + '&numOfRows=' + $NumOfRows
    return [uri]$uri
}

function Get-ExRestResponseStatus {
    param([int]$HttpStatus, [string]$Body)
    # Do not echo response text, URLs, keys, facility names or coordinates.
    if ($HttpStatus -ge 300 -and $HttpStatus -lt 400) { return 'RedirectBlocked' }
    if ($HttpStatus -eq 400 -and $Body -match '<H1>Request Blocked</H1>') { return 'RequestBlocked' }
    if ($HttpStatus -eq 401 -or $HttpStatus -eq 403) { return 'AccessDenied' }
    if ($HttpStatus -lt 200 -or $HttpStatus -ge 300) { return 'HttpError' }
    try { $null = ConvertFrom-Json -InputObject $Body -ErrorAction Stop }
    catch { return 'UnexpectedResponse' }
    # HTTP 200 / JSON alone is not proof of successful authentication or usable places.
    return 'JsonReceivedSchemaUnverified'
}

function Invoke-ExRestProbe {
    param([Parameter(Mandatory)][string]$ConfigPath, [string]$RouteNo = '')
    $status = Get-RestApiKeyStatus -Path $ConfigPath
    if (-not $status.ExConfigured) { return [pscustomobject]@{ Provider = 'EX'; Status = 'MissingKey'; HttpStatus = $null } }
    $client = $null; $handler = $null; $response = $null
    try {
        $config = Get-Content -LiteralPath $ConfigPath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
        $uri = New-ExRestRequestUri -Key $config.exApiKey -RouteNo $RouteNo
        Add-Type -AssemblyName System.Net.Http
        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.AllowAutoRedirect = $false
        $client = [System.Net.Http.HttpClient]::new($handler)
        $client.DefaultRequestHeaders.UserAgent.ParseAdd('DriveMate-API-Probe/1.0')
        $client.DefaultRequestHeaders.Accept.ParseAdd('application/json')
        $client.Timeout = [TimeSpan]::FromSeconds(15)
        $client.MaxResponseContentBufferSize = 2MB
        $response = $client.GetAsync($uri).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $code = [int]$response.StatusCode
        return [pscustomobject]@{ Provider = 'EX'; Status = (Get-ExRestResponseStatus -HttpStatus $code -Body $body); HttpStatus = $code }
    } catch {
        # Underlying exceptions can contain the query-string credential.
        return [pscustomobject]@{ Provider = 'EX'; Status = 'RequestFailed'; HttpStatus = $null }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        if ($null -ne $client) { $client.Dispose() }
        elseif ($null -ne $handler) { $handler.Dispose() }
    }
}

Export-ModuleMember -Function Get-RestApiKeyStatus, New-ExRestRequestUri, Get-ExRestResponseStatus, Invoke-ExRestProbe
