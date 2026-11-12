param(
    [Parameter(Mandatory=$true)][guid]$AccountId,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_.@-]{3,100}$')][string]$Operator,
    [switch]$PlanOnly,
    [switch]$Apply,
    [switch]$Revoke
)
$ErrorActionPreference = 'Stop'
if ($PlanOnly -and $Apply) { throw 'Choose either -PlanOnly or -Apply' }
. (Join-Path $PSScriptRoot 'assert-local-demo-target.ps1')
$id = Assert-LocalDemoTarget
$account = $AccountId.ToString()
$state = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT count(*) FROM account WHERE id='$account' AND status='ACTIVE'").Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne '1') { throw 'Account is not active in the dedicated demo database' }
$existing = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT coalesce((SELECT CASE WHEN revoked_at IS NULL THEN 'ACTIVE' ELSE 'REVOKED' END FROM source_reviewer_grant WHERE account_id='$account'),'NONE')").Trim()
if ($LASTEXITCODE -ne 0) { throw 'Reviewer grant plan query failed' }
$operation = if ($Revoke) { 'REVOKE' } else { 'GRANT' }
Write-Output "REVIEWER_PLAN account=$account current=$existing operation=$operation operator=$Operator"
if (-not $Apply) { Write-Output 'PLAN_ONLY: no reviewer authority changed'; return }
$confirmation = Read-Host "Type $operation $account to change only this account's local reviewer grant"
if ($confirmation -ne "$operation $account") { throw 'Confirmation did not match; no reviewer authority changed' }
if ($Revoke) {
    $sql = "UPDATE source_reviewer_grant SET revoked_at=CURRENT_TIMESTAMP,revoked_by='$Operator',revocation_reason='LOCAL_DEMO_OWNER_REVOCATION' WHERE account_id='$account' AND revoked_at IS NULL"
} elseif ($existing -eq 'REVOKED') {
    $sql = "UPDATE source_reviewer_grant SET granted_by='$Operator',granted_at=CURRENT_TIMESTAMP,revoked_at=NULL,revoked_by=NULL,revocation_reason=NULL WHERE account_id='$account' AND revoked_at IS NOT NULL"
} else {
    $sql = "INSERT INTO source_reviewer_grant(account_id,granted_by) SELECT id,'$Operator' FROM account WHERE id='$account' AND status='ACTIVE' ON CONFLICT (account_id) DO NOTHING"
}
& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -v ON_ERROR_STOP=1 -c $sql *> $null
if ($LASTEXITCODE -ne 0) { throw 'Reviewer grant change failed' }
$expected = if ($Revoke) { 'REVOKED' } else { 'ACTIVE' }
$after = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT CASE WHEN revoked_at IS NULL THEN 'ACTIVE' ELSE 'REVOKED' END FROM source_reviewer_grant WHERE account_id='$account'").Trim()
if ($LASTEXITCODE -ne 0 -or $after -ne $expected) { throw 'Reviewer grant verification failed' }
Write-Output "REVIEWER_$operation account=$account verified=$after"
