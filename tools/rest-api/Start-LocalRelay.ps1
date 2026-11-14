param([switch]$Serve, [switch]$EnableProviderRequests,
    [switch]$EnableLocationRequests,
    [ValidateRange(1024,65535)][int]$Port = 18765, [ValidateRange(1,1000)][int]$MaxRequests = 60)
$ErrorActionPreference = 'Stop'
if (-not $Serve) { Write-Output 'Not started. Use -Serve. Provider requests are disabled by default.'; exit 0 }
Import-Module (Join-Path $PSScriptRoot 'LocalRelay.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'PlacesApi.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'NearbyRelay.psm1') -Force
$localDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../.local'))
$null = New-Item -ItemType Directory -Force -Path $localDirectory
$tokenPath = Join-Path $localDirectory 'relay-session-token.txt'
$token = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$listener = [Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$clock = [Diagnostics.Stopwatch]::StartNew()
$lastProvider = -2000L
try {
    $listener.Start()
    [IO.File]::WriteAllText($tokenPath, $token)
    Write-Output 'Loopback relay ready. Session token is in the ignored .local directory. No transcript/request logging.'
    for ($i = 0; $i -lt $MaxRequests; $i++) {
        $context = $listener.GetContext()
        $stage = 'Request'
        try {
            $request = $context.Request
            $authorized = $request.Headers['Authorization'] -ceq "Bearer $token"
            $discovery = $request.HttpMethod -ceq 'GET' -and $request.Url.AbsolutePath -ceq '/v1/discovery' -and -not $request.Url.Query
            if ($request.Url.AbsolutePath -ceq '/v1/nearby') {
                $stage='Nearby'
                $enabled=$EnableProviderRequests -and $EnableLocationRequests
                $body=''
                $eligible=$authorized -and $enabled -and $request.HttpMethod -ceq 'POST' -and
                    -not $request.Url.Query -and $request.Headers['X-DriveMate-Location-Consent'] -ceq 'current-location-to-tmap'
                if ($eligible -and $clock.ElapsedMilliseconds - $lastProvider -lt 2000) {
                    $result=[pscustomobject]@{Code=429;Body=@{version=1;status='RateLimited';discoveries=@();safeCandidates=@()}}
                } else {
                    if ($eligible -and $request.ContentLength64 -in 1..1024 -and $request.ContentType -eq 'application/json') {
                        $buffer=[byte[]]::new([int]$request.ContentLength64)
                        $offset=0; $deadline=$clock.ElapsedMilliseconds+5000
                        while ($offset -lt $buffer.Length) {
                            $remaining=[int][Math]::Max(1,$deadline-$clock.ElapsedMilliseconds)
                            $read=$request.InputStream.ReadAsync($buffer,$offset,$buffer.Length-$offset)
                            if (-not $read.Wait($remaining)) { throw 'Body timeout.' }
                            $received=$read.GetAwaiter().GetResult()
                            if ($received -le 0) { throw 'Incomplete body.' }
                            $offset+=$received
                        }
                        $body=[Text.UTF8Encoding]::new($false,$true).GetString($buffer)
                    }
                    if ($eligible) { $lastProvider=$clock.ElapsedMilliseconds }
                    $result=Invoke-NearbyRelayRequest -Method $request.HttpMethod -Query $request.Url.Query `
                        -Authorization $request.Headers['Authorization'] -Token $token `
                        -Consent $request.Headers['X-DriveMate-Location-Consent'] -Body $body -Enabled:$enabled -Provider {
                            param($latitude,$longitude)
                            $providerReply = Invoke-TmapPlaceSearch -ConfigPath (Join-Path $localDirectory 'rest-api-keys.json') `
                                -Nearby @{Latitude=$latitude;Longitude=$longitude}
                            $providerStatus = if ($providerReply.Status -cin @('PageValidated','DataRejected','InvalidDataOrConfiguration','RequestFailed','AccessDenied','InvalidApiKey','NoContent','RequestRejected','HttpError','UnexpectedResponse','EmptyResponse')) { $providerReply.Status } else { 'ProviderFailure' }
                            Write-Information "Nearby provider status=$providerStatus" -InformationAction Continue
                            if ($providerReply.PSObject.Properties['DiagnosticStage'] -and $providerReply.DiagnosticStage -cin @('Configuration','RequestUri','Transport','ResponseSchema')) {
                                Write-Information "Nearby provider stage=$($providerReply.DiagnosticStage)" -InformationAction Continue
                                if ($providerReply.DiagnosticStage -ceq 'ResponseSchema') {
                                    Write-Information "Nearby schema requestedCountMatches=$($providerReply.Schema.CountMatchesRequested),totalCountMatches=$($providerReply.Schema.CountMatchesTotal),itemsArray=$($providerReply.Schema.ItemsAreArray),singleObject=$($providerReply.Schema.SingleItemObject)" -InformationAction Continue
                                }
                            }
                            $providerReply
                        }
                }
            } elseif ($request.HasEntityBody) {
                $result = [pscustomobject]@{ Code = 400; Body = @{version=1;status='BodyNotAllowed';safeCandidates=@();discoveries=@()} }
            } elseif ($authorized -and $discovery -and $EnableProviderRequests -and $clock.ElapsedMilliseconds - $lastProvider -lt 2000) {
                $result = [pscustomobject]@{ Code = 429; Body = @{version=1;status='RateLimited';safeCandidates=@();discoveries=@()} }
            } else {
                $stage = 'Dispatch'
                if ($authorized -and $discovery -and $EnableProviderRequests) { $lastProvider = $clock.ElapsedMilliseconds }
                $result = Invoke-LocalRelayRequest -Method $request.HttpMethod -Path $request.Url.AbsolutePath -Query $request.Url.Query `
                    -Authorization $request.Headers['Authorization'] -Token $token -EnableProviderRequests:$EnableProviderRequests -Provider {
                        Invoke-TmapPlaceSearch -ConfigPath (Join-Path $localDirectory 'rest-api-keys.json') -Keyword '휴게소' -Rows 20
                    }
            }
            $stage = 'Serialize'
            $bytes = [Text.Encoding]::UTF8.GetBytes(($result.Body | ConvertTo-Json -Depth 6 -Compress))
            $context.Response.StatusCode = $result.Code
            $context.Response.ContentType = 'application/json; charset=utf-8'
            $context.Response.Headers['Cache-Control'] = 'no-store'
            $context.Response.KeepAlive = $false
            $context.Response.ContentLength64 = $bytes.Length
            $stage = 'Write'
            # Complete the whole response synchronously before MaxRequests closes the listener.
            $context.Response.Close($bytes, $false)
        } catch {
            # Type name and a fixed stage only; no exception message, URL, key or payload.
            Write-Output "Relay failure stage=$stage category=$($_.Exception.GetType().Name)"
            try { $context.Response.StatusCode = 500 } catch {}
        }
        finally { $context.Response.Close() }
    }
    # HttpListener.Close aborts HTTP.sys queued sends even after Response.Close returns.
    # This development request cap uses a bounded drain period, not an immediate shutdown.
    Start-Sleep -Milliseconds 1000
} catch { Write-Output 'Relay stopped or could not start. Details suppressed.'; exit 1 }
finally {
    $listener.Close()
    # No recursive deletion; remove only this run's credential if it still belongs to us.
    if ((Test-Path -LiteralPath $tokenPath) -and [IO.File]::ReadAllText($tokenPath) -ceq $token) {
        Remove-Item -LiteralPath $tokenPath
    }
}
