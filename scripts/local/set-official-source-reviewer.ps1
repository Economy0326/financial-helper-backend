param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{32}$')][string]$RunId,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$ContainerId,
    [Parameter(Mandatory=$true)][guid]$AccountId,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_.@-]{3,100}$')][string]$Operator,
    [switch]$Revoke
)
$ErrorActionPreference = 'Stop'
$container = (& docker inspect $ContainerId 2>$null | ConvertFrom-Json)[0]
if ($LASTEXITCODE -ne 0 -or -not $container -or $container.Id -ne $ContainerId -or
        $container.Name -ne ('/fh-official-demo-' + $RunId) -or
        $container.Config.Labels.'financial-helper.official-demo.run' -ne $RunId -or
        -not $container.HostConfig.Tmpfs.'/var/lib/postgresql/data' -or
        $container.Mounts.Count -ne 0 -or
        $container.NetworkSettings.Ports.'5432/tcp'[0].HostIp -ne '127.0.0.1') {
    throw 'Reviewer grant target is not the verified dedicated demo container'
}
$databaseName = 'fh_official_demo_' + $RunId
$databaseUser = 'fh_demo_' + $RunId.Substring(0, 12)
$identity = $AccountId.ToString()
if ($Revoke) {
    $sql = "UPDATE source_reviewer_grant SET revoked_at=CURRENT_TIMESTAMP, revoked_by='$Operator', revocation_reason='MANUAL_OWNER_REVOCATION' WHERE account_id='$identity' AND revoked_at IS NULL"
} else {
    $sql = "INSERT INTO source_reviewer_grant(account_id,granted_by) SELECT id,'$Operator' FROM account WHERE id='$identity' AND status='ACTIVE' ON CONFLICT (account_id) DO NOTHING"
}
& docker exec $ContainerId psql -U $databaseUser -d $databaseName -v ON_ERROR_STOP=1 -c $sql *> $null
if ($LASTEXITCODE -ne 0) { throw 'Reviewer grant update failed' }
$verify = if ($Revoke) { "SELECT count(*) FROM source_reviewer_grant WHERE account_id='$identity' AND revoked_at IS NOT NULL" }
          else { "SELECT count(*) FROM source_reviewer_grant WHERE account_id='$identity' AND revoked_at IS NULL" }
$count = (& docker exec $ContainerId psql -U $databaseUser -d $databaseName -tA -v ON_ERROR_STOP=1 -c $verify).Trim()
if ($LASTEXITCODE -ne 0 -or $count -ne '1') { throw 'Reviewer grant state was not verified' }
Write-Output $(if ($Revoke) { 'Reviewer grant revoked in dedicated demo DB' } else { 'Reviewer grant active in dedicated demo DB' })
