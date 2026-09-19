param(
    [string]$DatabaseUrl = $env:DB_URL,
    [string]$DatabaseUsername = $env:DB_USERNAME,
    [string]$DatabasePassword = $env:DB_PASSWORD,
    [string]$ConfirmReset,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$requiredConfirmation = 'RESET_LOCAL_QA'
$runtimeTables = @(
    'consultation_report', 'analysis_evidence_snapshot', 'financial_action_plan',
    'confirmed_case_snapshot', 'retrieval_search_context', 'consultation_summary',
    'follow_up_question', 'case_understanding', 'analysis_job',
    'account_consultation_start', 'emergency_history', 'emergency_selection',
    'consultation', 'oauth_login_state', 'account_ai_usage', 'account_session',
    'account_identity', 'guest_session', 'account'
)
$preservedTables = @(
    'official_source_domain', 'source_registry', 'source_document',
    'source_chunk_configuration', 'source_chunk', 'source_chunk_indexing',
    'retrieval_generation', 'active_retrieval_generation', 'procedure_version',
    'emergency_scenario', 'flyway_schema_history'
)

if ($PlanOnly) {
    Write-Host ('DELETE:   ' + ($runtimeTables -join ', '))
    Write-Host ('PRESERVE: ' + ($preservedTables -join ', '))
    exit 0
}

if ($ConfirmReset -ne $requiredConfirmation) {
    throw "Refusing reset. Pass -ConfirmReset $requiredConfirmation explicitly."
}
if ([string]::IsNullOrWhiteSpace($DatabaseUrl)) {
    throw 'DB_URL or -DatabaseUrl is required.'
}
if (($env:SPRING_PROFILES_ACTIVE -split ',') -contains 'prod') {
    throw 'Refusing reset while the prod Spring profile is active.'
}

$postgresUrl = $DatabaseUrl -replace '^jdbc:', ''
try { $uri = [Uri]$postgresUrl } catch { throw 'DB_URL is not a valid PostgreSQL URL.' }
if ($uri.Scheme -notin @('postgresql', 'postgres')) {
    throw 'Only PostgreSQL URLs are accepted.'
}
if ($uri.Host -notin @('localhost', '127.0.0.1', '::1')) {
    throw 'Refusing reset: the database host is not local loopback.'
}

$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) { throw 'psql is required and was not found on PATH.' }

$countRows = $preservedTables | ForEach-Object {
    "SELECT '$($_)' AS table_name, count(*)::bigint AS row_count FROM $($_)"
}
$capture = ($countRows -join " UNION ALL ")
$deletes = ($runtimeTables | ForEach-Object { "DELETE FROM $($_);" }) -join "`n"
$sql = @"
BEGIN;
CREATE TEMP TABLE qa_preserved_counts AS $capture;
$deletes
DO `$`$
DECLARE changed_count integer;
BEGIN
  SELECT count(*) INTO changed_count
  FROM qa_preserved_counts before
  JOIN LATERAL (
    SELECT CASE before.table_name
$(($preservedTables | ForEach-Object { "      WHEN '$($_)' THEN (SELECT count(*) FROM $($_))" }) -join "`n")
    END::bigint AS row_count
  ) after ON true
  WHERE before.row_count <> after.row_count;
  IF changed_count <> 0 THEN
    RAISE EXCEPTION 'Preserved corpus/procedure/generation data changed';
  END IF;
END `$`$;
COMMIT;
"@

$tempSql = Join-Path ([IO.Path]::GetTempPath()) ("financial-helper-reset-" + [guid]::NewGuid() + '.sql')
$oldPassword = $env:PGPASSWORD
try {
    [IO.File]::WriteAllText($tempSql, $sql, [Text.UTF8Encoding]::new($false))
    if (-not [string]::IsNullOrWhiteSpace($DatabasePassword)) { $env:PGPASSWORD = $DatabasePassword }
    $arguments = @('--no-psqlrc', '--set', 'ON_ERROR_STOP=1', '--dbname', $postgresUrl, '--file', $tempSql)
    if (-not [string]::IsNullOrWhiteSpace($DatabaseUsername)) { $arguments += @('--username', $DatabaseUsername) }
    & $psql.Source @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Local QA reset failed; the transaction was rolled back.' }
    Write-Host 'Local QA runtime data reset completed. Reviewed corpus, procedures, retrieval generations, and Flyway history were preserved.'
} finally {
    $env:PGPASSWORD = $oldPassword
    Remove-Item -LiteralPath $tempSql -Force -ErrorAction SilentlyContinue
}
