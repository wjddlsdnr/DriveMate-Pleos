param([switch]$LiveEx, [string]$RouteNo = '')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'RestApi.psm1') -Force
$configPath = Join-Path $PSScriptRoot '../../.local/rest-api-keys.json'
try {
    Get-RestApiKeyStatus -Path $configPath | Format-List
    if ($LiveEx) {
        Invoke-ExRestProbe -ConfigPath $configPath -RouteNo $RouteNo | Format-List
    } else {
        Write-Output 'Local configuration check only. No network request was made.'
    }
} catch {
    Write-Output 'Configuration check failed. Check the JSON format. Key values were not printed.'
    exit 1
}
