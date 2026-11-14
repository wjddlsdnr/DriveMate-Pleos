$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'RestApi.psm1') -Force
$count = 0
function Assert-Equal($Expected, $Actual) {
    if ($Expected -cne $Actual) { throw 'Offline assertion failed (values omitted).' }
    $script:count++
}
$tempFile = Join-Path ([IO.Path]::GetTempPath()) ('drivemate-api-test-' + [guid]::NewGuid() + '.json')
try {
    Assert-Equal $false (Get-RestApiKeyStatus -Path $tempFile).ExConfigured
    Set-Content -LiteralPath $tempFile -Value '{"tmapAppKey":"fixture-only","exApiKey":""}'
    Assert-Equal $true (Get-RestApiKeyStatus -Path $tempFile).TmapConfigured
    Assert-Equal 'MissingKey' (Invoke-ExRestProbe -ConfigPath $tempFile).Status
    $uri = New-ExRestRequestUri -Key 'fixture+&=/only' -RouteNo '0010'
    Assert-Equal 'https' $uri.Scheme
    Assert-Equal 'data.ex.co.kr' $uri.Host
    Assert-Equal '?type=json&key=fixture%2B%26%3D%2Fonly&routeNo=0010&pageNo=1&numOfRows=10' $uri.Query
    $rejected = $false
    try { $null = New-ExRestRequestUri -Key 'fixture-only' -RouteNo '1&key=wrong' } catch { $rejected = $true }
    Assert-Equal $true $rejected
    Assert-Equal 'RedirectBlocked' (Get-ExRestResponseStatus 302 'ignored')
    Assert-Equal 'AccessDenied' (Get-ExRestResponseStatus 403 'ignored')
    Assert-Equal 'HttpError' (Get-ExRestResponseStatus 503 'ignored')
    Assert-Equal 'RequestBlocked' (Get-ExRestResponseStatus 400 '<HTML><H1>Request Blocked</H1></HTML>')
    Assert-Equal 'UnexpectedResponse' (Get-ExRestResponseStatus 200 '<html>error</html>')
    Assert-Equal 'JsonReceivedSchemaUnverified' (Get-ExRestResponseStatus 200 '{"error":"invalid key"}')
    Set-Content -LiteralPath $tempFile -Value '{bad-json'
    $message = ''
    try { $null = Get-RestApiKeyStatus -Path $tempFile } catch { $message = $_.Exception.Message }
    Assert-Equal 'Key configuration is not valid JSON. Values have not been printed.' $message
    Write-Output "PASS: $count offline checks; no API requests."
} finally {
    if (Test-Path -LiteralPath $tempFile) { Remove-Item -LiteralPath $tempFile }
}
