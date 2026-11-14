Set-StrictMode -Version Latest

function Get-RestPlaceNameKey {
    param([string]$Name)
    if ([string]::IsNullOrWhiteSpace($Name) -or $Name.Length -gt 100 -or $Name -match '[\x00-\x1F\x7F]') { return $null }
    $text = ($Name.Normalize([Text.NormalizationForm]::FormKC) -replace '\s+', '')
    # Preserve direction labels exactly; no guesses that 부산/하행/남쪽 mean the same thing.
    $base = ''; $direction = ''
    if ($text -match '^(?<base>[^()]+?)\((?<direction>[^()]+)\)(?:휴게소)?$') {
        $base = $Matches['base'] -replace '휴게소$', ''
        $direction = $Matches['direction'] -replace '방향$', ''
    } elseif ($text -match '^(?<base>[^()]+?)휴게소(?<direction>[^()]+)방향$') {
        $base = $Matches['base']; $direction = $Matches['direction']
    } else { return $null }
    if (-not $base -or -not $direction) { return $null }
    return $base + '|' + $direction
}

function Compare-RestPlaceCatalogs {
    param([Parameter(Mandatory)]$ExCatalog, [Parameter(Mandatory)]$TmapPage)
    if ($ExCatalog.Status -cne 'Complete' -or $TmapPage.Status -cne 'PageValidated') {
        return [pscustomobject]@{ Status='InputNotComplete'; Comparisons=@(); SafeCandidates=@() }
    }
    $exIndex = @{}; $tmapIndex = @{}
    $seenEx = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $seenTmap = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $duplicateTmap = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in @(@{Items=$ExCatalog.Records;Index=$exIndex;Seen=$seenEx;Provider='EX'},
                         @{Items=$TmapPage.Records;Index=$tmapIndex;Seen=$seenTmap;Provider='TMAP'})) {
        foreach ($item in $entry.Items) {
            if ($item.Provider -cne $entry.Provider -or [string]::IsNullOrWhiteSpace($item.SourceId)) {
                return [pscustomobject]@{Status='AmbiguousInput';Comparisons=@();SafeCandidates=@()}
            }
            if (-not $entry.Seen.Add($item.SourceId)) {
                if ($entry.Provider -ceq 'EX') { return [pscustomobject]@{Status='AmbiguousInput';Comparisons=@();SafeCandidates=@()} }
                $null = $duplicateTmap.Add($item.SourceId)
            }
            $key = Get-RestPlaceNameKey -Name $item.Name
            if ($null -eq $key) { continue }
            if (-not $entry.Index.ContainsKey($key)) { $entry.Index[$key] = @() }
            $entry.Index[$key] += $item
        }
    }
    $results = [Collections.Generic.List[object]]::new()
    foreach ($key in $exIndex.Keys) {
        $matches = @(if ($tmapIndex.ContainsKey($key)) { $tmapIndex[$key] })
        foreach ($ex in $exIndex[$key]) {
            $oneToOne = $exIndex[$key].Count -eq 1 -and $matches.Count -eq 1 -and
                -not $duplicateTmap.Contains($matches[0].SourceId)
            $results.Add([pscustomobject]@{
                ExId=$ex.SourceId
                TmapId=$(if ($oneToOne) {$matches[0].SourceId} else {$null})
                Status=$(if ($oneToOne) {'NameDirectionAgreement'} elseif ($matches.Count -gt 0) {'Ambiguous'} else {'NotInFetchedPage'})
                # Name agreement is a review lead, not a cross-provider identity or access proof.
                IdentityVerified=$false; CoordinateAgreement=$null
                DirectionVerified=$false; AccessVerified=$false; ParkingVerified=$false
            })
        }
    }
    return [pscustomobject]@{Status='ComparedFetchedPageOnly';Comparisons=$results.ToArray();SafeCandidates=@()}
}

Export-ModuleMember -Function Get-RestPlaceNameKey, Compare-RestPlaceCatalogs
