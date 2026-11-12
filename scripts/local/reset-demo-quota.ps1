param(
    [Parameter(Mandatory=$true)][guid]$AccountId,
    [ValidateRange(5,100)][int]$NewLimit = 20,
    [ValidateRange(1,7)][int]$ValidDays = 7,
    [ValidatePattern('^[A-Za-z0-9_.@-]{3,100}$')][string]$Operator,
    [ValidatePattern('^[a-f0-9]{32}$')][string]$FixtureRun,
    [switch]$PlanOnly,
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
if ($Apply -and $PlanOnly) { throw 'Choose either -PlanOnly or -Apply' }
if ($Apply -and -not $Operator) { throw '-Operator is required for an audited change' }
. (Join-Path $PSScriptRoot 'assert-local-demo-target.ps1')
$id = Assert-LocalDemoTarget -FixtureRun $FixtureRun
$account = $AccountId.ToString()
$state = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT count(*) FROM account WHERE id='$account' AND status='ACTIVE'").Trim()
if ($LASTEXITCODE -ne 0 -or $state -ne '1') { throw 'Target active account was not found in dedicated demo DB' }
$before = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT (SELECT count(*) FROM account_consultation_start WHERE account_id='$account' AND started_at > now() - interval '7 days') || '|' || coalesce((SELECT new_consultation_limit::text FROM local_demo_consultation_quota_override WHERE account_id='$account' AND expires_at > now()),'5')").Trim()
if ($LASTEXITCODE -ne 0) { throw 'Quota plan query failed' }
Write-Output "QUOTA_PLAN account=$account used_and_limit=$before proposed_limit=$NewLimit valid_days=$ValidDays"
if (-not $Apply) { Write-Output 'PLAN_ONLY: no records changed'; return }
$confirmation = Read-Host "Type the Account ID $account to change only its local demo quota"
if ($confirmation -ne $account) { throw 'Confirmation did not match Account ID; no records changed' }
$sql = "BEGIN; SELECT 1/(CASE WHEN EXISTS (SELECT 1 FROM account WHERE id='$account' AND status='ACTIVE') THEN 1 ELSE 0 END); INSERT INTO local_demo_consultation_quota_audit(id,account_id,previous_limit,new_limit,expires_at,operator_note) VALUES (gen_random_uuid(),'$account',(SELECT new_consultation_limit FROM local_demo_consultation_quota_override WHERE account_id='$account'),$NewLimit,now() + interval '$ValidDays days','$Operator'); INSERT INTO local_demo_consultation_quota_override(account_id,new_consultation_limit,expires_at) VALUES ('$account',$NewLimit,now() + interval '$ValidDays days') ON CONFLICT (account_id) DO UPDATE SET new_consultation_limit=EXCLUDED.new_consultation_limit,expires_at=EXCLUDED.expires_at,changed_at=now(); COMMIT;"
& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -v ON_ERROR_STOP=1 -c $sql *> $null
if ($LASTEXITCODE -ne 0) { throw 'Quota override failed; transaction rolled back' }
$after = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT new_consultation_limit FROM local_demo_consultation_quota_override WHERE account_id='$account' AND expires_at > now()").Trim()
if ($LASTEXITCODE -ne 0 -or $after -ne "$NewLimit") { throw 'Quota override verification failed' }
Write-Output "QUOTA_APPLIED account=$account new_limit=$after; consultation and review history preserved"
