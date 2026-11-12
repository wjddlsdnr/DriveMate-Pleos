Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'RestApi.psm1') -Force

function Read-Field($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -ne $property) { return ,$property.Value }
    return $null
}
function Read-Integer($Value) {
    $number = 0
    if ($null -eq $Value -or "$Value" -notmatch '^\d{1,8}$' -or -not [int]::TryParse("$Value", [ref]$number)) {
        throw 'Invalid numeric metadata.'
    }
    return $number
}
function Read-Coordinate($Value, [double]$Minimum, [double]$Maximum) {
    $number = 0.0
    if ($null -eq $Value -or -not [double]::TryParse("$Value", [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$number) -or
        [double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -lt $Minimum -or $number -gt $Maximum) {
        throw 'Invalid coordinate.'
    }
    return $number
}
function Read-Text($Value, [int]$Limit = 300) {
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value) -or
        $Value.Length -gt $Limit -or $Value -match '[\x00-\x1F\x7F]') { throw 'Invalid text.' }
    return $Value.Trim()
}
function New-TmapPlaceRequestUri {
    param([Parameter(Mandatory)][string]$Keyword, [ValidateRange(1,200)][int]$Page = 1,
        [ValidateRange(1,200)][int]$Count = 20)
    $keywordValue = Read-Text $Keyword 100
    return [uri]('https://apis.openapi.sk.com/tmap/pois?version=1&searchType=all&searchKeyword=' +
        [uri]::EscapeDataString($keywordValue) + '&reqCoordType=WGS84GEO&resCoordType=WGS84GEO&multiPoint=Y&page=' + $Page + '&count=' + $Count)
}

function ConvertFrom-ExRestPage {
    param([Parameter(Mandatory)]$Data, [Parameter(Mandatory)][int]$ExpectedPage,
        [Parameter(Mandatory)][int]$ExpectedRows)
    if ((Read-Field $Data 'code') -cne 'SUCCESS') { throw 'Provider rejected request.' }
    $total = Read-Integer (Read-Field $Data 'count')
    $page = Read-Integer (Read-Field $Data 'pageNo')
    $rows = Read-Integer (Read-Field $Data 'numOfRows')
    $pageSize = Read-Integer (Read-Field $Data 'pageSize')
    $items = Read-Field $Data 'list'
    if ($page -ne $ExpectedPage -or $rows -ne $ExpectedRows -or $rows -lt 1 -or $items -isnot [array] -or
        $pageSize -ne [Math]::Ceiling($total / [double]$rows)) { throw 'Invalid page metadata.' }
    $expectedCount = [Math]::Min($rows, [Math]::Max(0, $total - ($page - 1) * $rows))
    if ($items.Count -ne $expectedCount) { throw 'Incomplete page.' }
    $records = [Collections.Generic.List[object]]::new()
    $rejected = 0
    foreach ($item in $items) {
        try {
            $id = Read-Text (Read-Field $item 'unitCode') 80
            $name = Read-Text (Read-Field $item 'unitName') 100
            $route = Read-Text (Read-Field $item 'routeNo') 20
            $routeName = Read-Text (Read-Field $item 'routeName') 100
            $x = Read-Coordinate (Read-Field $item 'xValue') -180 180
            $y = Read-Coordinate (Read-Field $item 'yValue') -90 90
            # The source's CRS and route direction are not yet independently verified.
            $records.Add([pscustomobject]@{ Provider = 'EX'; SourceId = $id; Name = $name; RouteNo = $route;
                RouteName = $routeName; SourceX = $x; SourceY = $y; CoordinateSystem = 'Unverified';
                DirectionVerified = $false; AccessVerified = $false; ParkingVerified = $false })
        } catch { $rejected++ }
    }
    return [pscustomobject]@{ Total = $total; Page = $page; PageCount = $pageSize;
        SourceCount = $items.Count; Rejected = $rejected; Records = $records.ToArray() }
}

function ConvertFrom-TmapPlacePage {
    param([Parameter(Mandatory)]$Data, [Parameter(Mandatory)][int]$ExpectedPage,
        [Parameter(Mandatory)][int]$ExpectedRows)
    if ($null -ne (Read-Field $Data 'error')) { throw 'Provider rejected request.' }
    $info = Read-Field $Data 'searchPoiInfo'
    $total = Read-Integer (Read-Field $info 'totalCount')
    $page = Read-Integer (Read-Field $info 'page')
    $rows = Read-Integer (Read-Field $info 'count')
    $items = Read-Field (Read-Field $info 'pois') 'poi'
    if ($null -eq $items -and $total -eq 0) { $items = @() }
    if ($page -ne $ExpectedPage -or $rows -ne $ExpectedRows -or $rows -lt 1 -or $items -isnot [array]) { throw 'Invalid page metadata.' }
    $expectedCount = [Math]::Min($rows, [Math]::Max(0, $total - ($page - 1) * $rows))
    if ($items.Count -ne $expectedCount) { throw 'Incomplete page.' }
    $records = [Collections.Generic.List[object]]::new()
    $rejected = 0
    foreach ($item in $items) {
        try {
            $id = Read-Text (Read-Field $item 'id') 80
            $name = Read-Text (Read-Field $item 'name') 100
            $latitude = Read-Coordinate (Read-Field $item 'frontLat') -90 90
            $longitude = Read-Coordinate (Read-Field $item 'frontLon') -180 180
            $records.Add([pscustomobject]@{ Provider = 'TMAP'; SourceId = $id; Name = $name;
                Latitude = $latitude; Longitude = $longitude; CoordinateSystem = 'WGS84GEO';
                DirectionVerified = $false; AccessVerified = $false; ParkingVerified = $false })
        } catch { $rejected++ }
    }
    return [pscustomobject]@{ Total = $total; Page = $page; SourceCount = $items.Count;
        Rejected = $rejected; Records = $records.ToArray() }
}

function Invoke-PlacesJson {
    param([uri]$Uri, [string]$AppKey = '')
    # Fixed official endpoints only, no redirects, no URL/response/error logging.
    if ($Uri.Scheme -ne 'https' -or $Uri.Host -notin @('data.ex.co.kr', 'apis.openapi.sk.com')) { throw 'Invalid endpoint.' }
    $client = $null; $handler = $null; $response = $null
    try {
        Add-Type -AssemblyName System.Net.Http
        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.AllowAutoRedirect = $false
        $client = [System.Net.Http.HttpClient]::new($handler)
        $client.Timeout = [TimeSpan]::FromSeconds(15)
        $client.MaxResponseContentBufferSize = 2MB
        $client.DefaultRequestHeaders.UserAgent.ParseAdd('DriveMate-API-Probe/1.0')
        $client.DefaultRequestHeaders.Accept.ParseAdd('application/json')
        if ($AppKey) { $client.DefaultRequestHeaders.Add('appKey', $AppKey) }
        $response = $client.GetAsync($Uri).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $status = Get-ExRestResponseStatus -HttpStatus ([int]$response.StatusCode) -Body $body
        if ($status -eq 'AccessDenied') {
            try {
                $failure = ConvertFrom-Json -InputObject $body -ErrorAction Stop
                if ((Read-Field (Read-Field $failure 'error') 'code') -ceq 'INVALID_API_KEY') { $status = 'InvalidApiKey' }
            } catch { }
        }
        if ($status -ne 'JsonReceivedSchemaUnverified') { return [pscustomobject]@{ Status = $status; Data = $null } }
        return [pscustomobject]@{ Status = 'JsonReceived'; Data = (ConvertFrom-Json -InputObject $body -ErrorAction Stop) }
    } catch { return [pscustomobject]@{ Status = 'RequestFailed'; Data = $null } }
    finally {
        if ($null -ne $response) { $response.Dispose() }
        if ($null -ne $client) { $client.Dispose() } elseif ($null -ne $handler) { $handler.Dispose() }
    }
}

function Invoke-ExRestCatalog {
    param([Parameter(Mandatory)][string]$ConfigPath, [string]$RouteNo = '',
        [ValidateRange(1,100)][int]$PageLimit = 5, [ValidateRange(1,99)][int]$Rows = 99,
        [scriptblock]$Transport)
    $records = [Collections.Generic.List[object]]::new()
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $pages = 0; $total = $null; $rejected = 0; $duplicates = 0; $status = 'Incomplete'
    try {
        if (-not (Get-RestApiKeyStatus -Path $ConfigPath).ExConfigured) { throw 'Missing configuration.' }
        $key = (Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json).exApiKey
        for ($pageNo = 1; $pageNo -le $PageLimit; $pageNo++) {
            $uri = New-ExRestRequestUri -Key $key -RouteNo $RouteNo -PageNo $pageNo -NumOfRows $Rows
            $response = if ($null -ne $Transport) { & $Transport $uri } else { Invoke-PlacesJson -Uri $uri }
            if ($response.Status -ne 'JsonReceived') { $status = $response.Status; break }
            $page = ConvertFrom-ExRestPage -Data $response.Data -ExpectedPage $pageNo -ExpectedRows $Rows
            if ($null -ne $total -and $total -ne $page.Total) { throw 'Catalog changed during pagination.' }
            $total = $page.Total; $pages++; $rejected += $page.Rejected
            foreach ($record in $page.Records) {
                if ($RouteNo -and $record.RouteNo -cne $RouteNo) { throw 'Route filter mismatch.' }
                if ($ids.Add($record.SourceId)) { $records.Add($record) } else { $duplicates++ }
            }
            if ($pageNo -ge $page.PageCount) {
                $status = if ($rejected -eq 0 -and $duplicates -eq 0 -and $records.Count -eq $total) { 'Complete' } else { 'DataRejected' }
                break
            }
        }
    } catch { $status = 'InvalidDataOrConfiguration' }
    # Partial or inconsistent catalogs must not look usable to callers.
    return [pscustomobject]@{ Provider = 'EX'; Status = $status; Pages = $pages; Total = $total;
        ValidatedCount = $records.Count; Rejected = $rejected; Duplicates = $duplicates;
        Records = @(if ($status -eq 'Complete') { $records.ToArray() }) }
}

function Invoke-TmapPlaceSearch {
    param([Parameter(Mandatory)][string]$ConfigPath, [string]$Keyword = '휴게소',
        [ValidateRange(1,200)][int]$Page = 1, [ValidateRange(1,200)][int]$Rows = 20,
        [scriptblock]$Transport)
    try {
        if (-not (Get-RestApiKeyStatus -Path $ConfigPath).TmapConfigured) { throw 'Missing configuration.' }
        $key = (Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json).tmapAppKey
        $uri = New-TmapPlaceRequestUri -Keyword $Keyword -Page $Page -Count $Rows
        $response = if ($null -ne $Transport) { & $Transport $uri $key } else { Invoke-PlacesJson -Uri $uri -AppKey $key }
        if ($response.Status -ne 'JsonReceived') { return [pscustomobject]@{ Provider = 'TMAP'; Status = $response.Status; Total = $null; Records = @() } }
        $result = ConvertFrom-TmapPlacePage -Data $response.Data -ExpectedPage $Page -ExpectedRows $Rows
        return [pscustomobject]@{ Provider = 'TMAP'; Status = $(if ($result.Rejected -eq 0) {'PageValidated'} else {'DataRejected'});
            Total = $result.Total; Page = $Page; Rejected = $result.Rejected;
            Records = @(if ($result.Rejected -eq 0) { $result.Records }) }
    } catch { return [pscustomobject]@{ Provider = 'TMAP'; Status = 'InvalidDataOrConfiguration'; Total = $null; Records = @() } }
}

Export-ModuleMember -Function New-TmapPlaceRequestUri, ConvertFrom-ExRestPage, ConvertFrom-TmapPlacePage, Invoke-ExRestCatalog, Invoke-TmapPlaceSearch
