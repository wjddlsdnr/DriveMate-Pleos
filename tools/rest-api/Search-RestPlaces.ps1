param([switch]$ExCatalog, [switch]$Tmap, [string]$Keyword = '휴게소', [string]$RouteNo = '')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PlacesApi.psm1') -Force
$configPath = Join-Path $PSScriptRoot '../../.local/rest-api-keys.json'
if (-not $ExCatalog -and -not $Tmap) {
    Write-Output 'No network request made. Use -ExCatalog and/or -Tmap to request official data.'
    exit 0
}
try {
    if ($ExCatalog) {
        $result = Invoke-ExRestCatalog -ConfigPath $configPath -RouteNo $RouteNo
        $result | Select-Object Provider, Status, Pages, Total, ValidatedCount, Rejected, Duplicates | Format-List
    }
    if ($Tmap) {
        $result = Invoke-TmapPlaceSearch -ConfigPath $configPath -Keyword $Keyword
        $result | Select-Object Provider, Status, Page, Total, Rejected, @{Name='Returned';Expression={@($_.Records).Count}} | Format-List
    }
    Write-Output 'Read-only PC tool. No automatic route changes; direction/access/parking remain unverified.'
} catch {
    Write-Output 'Search failed. Sensitive details were suppressed.'
    exit 1
}
