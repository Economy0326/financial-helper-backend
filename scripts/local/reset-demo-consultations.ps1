param(
    [Parameter(Mandatory=$true)][guid]$AccountId,
    [ValidatePattern('^[a-f0-9]{32}$')][string]$FixtureRun,
    [switch]$PlanOnly,
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
if ($Apply -and $PlanOnly) { throw 'Choose either -PlanOnly or -Apply' }
. (Join-Path $PSScriptRoot 'assert-local-demo-target.ps1')
$id = Assert-LocalDemoTarget -FixtureRun $FixtureRun
$account = $AccountId.ToString()
$exists = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT count(*) FROM account WHERE id='$account' AND status='ACTIVE'").Trim()
if ($LASTEXITCODE -ne 0 -or $exists -ne '1') { throw 'Target active account was not found in dedicated demo DB' }
$unsafe = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 -c @'
SELECT count(*) FROM pg_constraint f JOIN pg_class c ON c.oid=f.conrelid
WHERE f.contype='f' AND f.confrelid='consultation'::regclass
AND f.confdeltype <> 'c' AND c.relname <> 'retrieval_search_context';
'@).Trim()
if ($LASTEXITCODE -ne 0 -or $unsafe -ne '0') { throw 'NOT_SAFE_TO_APPLY: unknown non-cascade consultation dependency' }
$rows = @(& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT id FROM consultation WHERE account_id='$account' ORDER BY id" | Where-Object { $_ -and $_.Trim() })
if ($LASTEXITCODE -ne 0) { throw 'Consultation plan query failed' }
$ids = @($rows | Where-Object { $_ -match '^[a-f0-9-]{36}$' })
if ($ids.Count -ne $rows.Count) { throw 'Consultation plan contained an unexpected identifier' }
$reportCount = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
        -c "SELECT count(*) FROM information_report r JOIN consultation c ON c.id=r.consultation_id WHERE c.account_id='$account'").Trim()
if ($LASTEXITCODE -ne 0) { throw 'Report plan query failed' }
Write-Output "CONSULTATION_CLEANUP_PLAN account=$account consultations=$($ids.Count) information_reports=$reportCount"
foreach ($consultationId in $ids) { Write-Output "consultation_id=$consultationId" }
if (-not $Apply) { Write-Output 'PLAN_ONLY: no records changed'; return }
if ($ids.Count -eq 0) { Write-Output 'NOTHING_TO_CLEAN'; return }
$confirmation = Read-Host "Type the Account ID $account to back up and delete only its consultations"
if ($confirmation -ne $account) { throw 'Confirmation did not match Account ID; no records changed' }
$backupDirectory = Join-Path $env:LOCALAPPDATA 'FinancialHelper\OfficialDemoBackups'
New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
$backupName = 'before-consultation-cleanup-' + [Guid]::NewGuid().ToString('N') + '.dump'
$backupPath = Join-Path $backupDirectory $backupName
$containerBackup = '/tmp/' + $backupName
try {
    & docker exec $id pg_dump -U fh_official_demo -d fh_official_local_demo -Fc -f $containerBackup *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Backup creation failed; no consultations were deleted' }
    & docker cp "${id}:$containerBackup" $backupPath *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $backupPath)) {
        throw 'Backup copy failed; no consultations were deleted'
    }
    $size = (Get-Item -LiteralPath $backupPath).Length
    if ($size -lt 1024) { throw 'Backup size invalid; no consultations were deleted' }
    $backupReference = $backupName
    $count = $ids.Count
    $sql = "BEGIN; CREATE TEMP TABLE demo_cleanup_baseline ON COMMIT DROP AS SELECT (SELECT count(*) FROM consultation WHERE account_id IS DISTINCT FROM '$account'::uuid) AS other_consultations, (SELECT count(*) FROM source_document) AS documents, (SELECT count(*) FROM source_applicability_review) AS reviews, (SELECT count(*) FROM source_reviewer_grant) AS reviewer_grants, (SELECT count(*) FROM source_review_decision_audit) AS review_audits, (SELECT count(*) FROM active_retrieval_generation) AS ready_pointers; SELECT 1/(CASE WHEN (SELECT count(*) FROM consultation WHERE account_id='$account')=$count THEN 1 ELSE 0 END); DELETE FROM retrieval_search_context WHERE consultation_id IN (SELECT id FROM consultation WHERE account_id='$account'); DELETE FROM consultation WHERE account_id='$account'; SELECT 1/(CASE WHEN (SELECT count(*) FROM consultation WHERE account_id='$account')=0 AND (SELECT count(*) FROM consultation WHERE account_id IS DISTINCT FROM '$account'::uuid)=(SELECT other_consultations FROM demo_cleanup_baseline) AND (SELECT count(*) FROM source_document)=(SELECT documents FROM demo_cleanup_baseline) AND (SELECT count(*) FROM source_applicability_review)=(SELECT reviews FROM demo_cleanup_baseline) AND (SELECT count(*) FROM source_reviewer_grant)=(SELECT reviewer_grants FROM demo_cleanup_baseline) AND (SELECT count(*) FROM source_review_decision_audit)=(SELECT review_audits FROM demo_cleanup_baseline) AND (SELECT count(*) FROM active_retrieval_generation)=(SELECT ready_pointers FROM demo_cleanup_baseline) THEN 1 ELSE 0 END); INSERT INTO local_demo_consultation_cleanup_audit(id,account_id,consultation_count,backup_reference) VALUES (gen_random_uuid(),'$account',$count,'$backupReference'); COMMIT;"
    & docker exec $id psql -U fh_official_demo -d fh_official_local_demo -v ON_ERROR_STOP=1 -c $sql *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Cleanup transaction failed; backup retained for inspection' }
    $remaining = (& docker exec $id psql -U fh_official_demo -d fh_official_local_demo -tA -v ON_ERROR_STOP=1 `
            -c "SELECT count(*) FROM consultation WHERE account_id='$account'").Trim()
    if ($LASTEXITCODE -ne 0 -or $remaining -ne '0') { throw 'Cleanup verification failed; inspect backup and database' }
    Write-Output "CONSULTATION_CLEANUP_APPLIED account=$account removed=$count backup=$backupPath"
    Write-Output 'Recovery: stop the demo, create a new verified local database, then use pg_restore on this backup; do not restore over an active database.'
} finally {
    & docker exec $id rm -f $containerBackup *> $null
}

