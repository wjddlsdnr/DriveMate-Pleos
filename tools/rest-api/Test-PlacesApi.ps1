$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PlacesApi.psm1') -Force
$count = 0
function Assert-Equal($Expected, $Actual) {
    if ($Expected -cne $Actual) { throw 'Offline places assertion failed (values omitted).' }
    $script:count++
}
function Assert-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { $null = & $Action } catch { $rejected = $true }
    Assert-Equal $true $rejected
}
function Ex-Item([string]$Id) {
    [pscustomobject]@{ unitCode=$Id; unitName='테스트 휴게소'; routeNo='0010'; routeName='테스트 노선'; xValue='127.1'; yValue='37.4' }
}
function Ex-Page([int]$Page, [int]$Total, [array]$Items) {
    [pscustomobject]@{ code='SUCCESS'; count=$Total; pageNo=$Page; numOfRows=2;
        pageSize=[int][Math]::Ceiling($Total/2.0); list=$Items }
}
$file = Join-Path ([IO.Path]::GetTempPath()) ('drivemate-places-test-' + [guid]::NewGuid() + '.json')
try {
    Set-Content -LiteralPath $file -Value '{"tmapAppKey":"fixture-only","exApiKey":"fixture-only"}'
    $uri = New-TmapPlaceRequestUri -Keyword '휴게소 &test' -Page 2 -Count 2
    Assert-Equal 'https' $uri.Scheme
    Assert-Equal 'apis.openapi.sk.com' $uri.Host
    Assert-Equal $true ($uri.Query.Contains('searchKeyword=%ED%9C%B4%EA%B2%8C%EC%86%8C%20%26test'))
    Assert-Equal $false ($uri.Query.Contains('appKey'))
    Assert-Rejected { New-TmapPlaceRequestUri -Keyword "bad`ntext" }
    Assert-Rejected { New-TmapPlaceRequestUri -Keyword 'place' -Page 201 }
    $page = ConvertFrom-ExRestPage -Data (Ex-Page 2 3 @((Ex-Item 'C'))) -ExpectedPage 2 -ExpectedRows 2
    Assert-Equal 1 $page.Records.Count
    Assert-Equal 'Unverified' $page.Records[0].CoordinateSystem
    Assert-Equal $false $page.Records[0].DirectionVerified
    Assert-Equal $false $page.Records[0].AccessVerified
    Assert-Equal $false $page.Records[0].ParkingVerified
    Assert-Rejected { ConvertFrom-ExRestPage (Ex-Page 1 3 @((Ex-Item 'A'))) 1 2 }
    Assert-Rejected { ConvertFrom-ExRestPage (Ex-Page 1 3 @((Ex-Item 'A'),(Ex-Item 'B'))) 2 2 }
    $errorPage = Ex-Page 1 0 @(); $errorPage.code = 'FAIL'
    Assert-Rejected { ConvertFrom-ExRestPage $errorPage 1 2 }
    $bad = Ex-Item 'bad'; $bad.xValue = 'NaN'
    $page = ConvertFrom-ExRestPage (Ex-Page 1 1 @($bad)) 1 2
    Assert-Equal 1 $page.Rejected
    Assert-Equal 0 $page.Records.Count
    $transport = {
        param($uri)
        $pageNo = if ($uri.Query.Contains('pageNo=1&')) {1} else {2}
        $items = if ($pageNo -eq 1) { @((Ex-Item 'A'),(Ex-Item 'B')) } else { @((Ex-Item 'C')) }
        [pscustomobject]@{ Status='JsonReceived'; Data=(Ex-Page $pageNo 3 @($items)) }
    }
    $catalog = Invoke-ExRestCatalog -ConfigPath $file -Rows 2 -Transport $transport
    Assert-Equal 'Complete' $catalog.Status
    Assert-Equal 3 $catalog.Records.Count
    Assert-Equal 2 $catalog.Pages
    $partial = Invoke-ExRestCatalog -ConfigPath $file -Rows 2 -PageLimit 1 -Transport $transport
    Assert-Equal 'Incomplete' $partial.Status
    Assert-Equal 0 $partial.Records.Count
    $failure = Invoke-ExRestCatalog -ConfigPath $file -Rows 2 -Transport {
        param($uri)
        if ($uri.Query.Contains('pageNo=1&')) { [pscustomobject]@{Status='JsonReceived';Data=(Ex-Page 1 3 @((Ex-Item 'A'),(Ex-Item 'B')))} }
        else { [pscustomobject]@{Status='RequestFailed';Data=$null} }
    }
    Assert-Equal 'RequestFailed' $failure.Status
    Assert-Equal 0 $failure.Records.Count
    $duplicates = Invoke-ExRestCatalog -ConfigPath $file -Rows 2 -Transport {
        param($uri)
        $pageNo = if ($uri.Query.Contains('pageNo=1&')) {1} else {2}
        $items = if ($pageNo -eq 1) { @((Ex-Item 'A'),(Ex-Item 'B')) } else { @((Ex-Item 'A')) }
        [pscustomobject]@{Status='JsonReceived';Data=(Ex-Page $pageNo 3 @($items))}
    }
    Assert-Equal 'DataRejected' $duplicates.Status
    Assert-Equal 1 $duplicates.Duplicates
    Assert-Equal 0 $duplicates.Records.Count
    $wrongRoute = Invoke-ExRestCatalog -ConfigPath $file -RouteNo '9999' -Rows 2 -Transport $transport
    Assert-Equal 'InvalidDataOrConfiguration' $wrongRoute.Status
    Assert-Equal 0 $wrongRoute.Records.Count
    $changed = Invoke-ExRestCatalog -ConfigPath $file -Rows 2 -Transport {
        param($uri)
        if ($uri.Query.Contains('pageNo=1&')) { [pscustomobject]@{Status='JsonReceived';Data=(Ex-Page 1 3 @((Ex-Item 'A'),(Ex-Item 'B')))} }
        else { [pscustomobject]@{Status='JsonReceived';Data=(Ex-Page 2 4 @((Ex-Item 'C'),(Ex-Item 'D')))} }
    }
    Assert-Equal 'InvalidDataOrConfiguration' $changed.Status
    Assert-Equal 0 $changed.Records.Count
    $tmapItem = [pscustomobject]@{id='test-tmap';name='테스트 장소';frontLat='37.4';frontLon='127.1'}
    $tmap = [pscustomobject]@{searchPoiInfo=[pscustomobject]@{totalCount='1';page='1';count='20';pois=[pscustomobject]@{poi=@($tmapItem)}}}
    $page = ConvertFrom-TmapPlacePage $tmap 1 20
    Assert-Equal 1 $page.Records.Count
    Assert-Equal 'TMAP' $page.Records[0].Provider
    Assert-Equal 'WGS84GEO' $page.Records[0].CoordinateSystem
    Assert-Equal $false $page.Records[0].AccessVerified
    $tmapItem.frontLat = '91'
    Assert-Equal 1 (ConvertFrom-TmapPlacePage $tmap 1 20).Rejected
    Assert-Rejected { ConvertFrom-TmapPlacePage ([pscustomobject]@{error=[pscustomobject]@{code='INVALID_API_KEY'}}) 1 20 }
    $empty = [pscustomobject]@{searchPoiInfo=[pscustomobject]@{totalCount='0';page='1';count='20'}}
    Assert-Equal 0 (ConvertFrom-TmapPlacePage $empty 1 20).Records.Count
    $empty.searchPoiInfo.count = '0'
    Assert-Equal 0 (ConvertFrom-TmapPlacePage $empty 1 20).Records.Count
    $tmapItem.frontLat = '37.4'
    $tmap.searchPoiInfo.count = '1'
    Assert-Equal 1 (ConvertFrom-TmapPlacePage $tmap 1 20).Records.Count
    $tmap.searchPoiInfo.totalCount = '2'
    Assert-Rejected { ConvertFrom-TmapPlacePage $tmap 1 20 }
    $denied = Invoke-TmapPlaceSearch -ConfigPath $file -Transport { param($uri,$key) [pscustomobject]@{Status='InvalidApiKey';Data=$null} }
    Assert-Equal 'InvalidApiKey' $denied.Status
    Assert-Equal 0 $denied.Records.Count
    $missing = Invoke-TmapPlaceSearch -ConfigPath ($file + '.missing') -Transport { throw 'Must not call transport' }
    Assert-Equal 'InvalidDataOrConfiguration' $missing.Status
    Write-Output "PASS: $count offline places checks; no API requests."
} finally {
    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
}
