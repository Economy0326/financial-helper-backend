# Run under Windows PowerShell 5.1 as well as PowerShell 7.
# Only a newly created, labeled, tmpfs PostgreSQL container is touched.
$ErrorActionPreference = 'Stop'
$run = [Guid]::NewGuid().ToString('N')
$name = 'fh-registry-sql-test-' + $run
$containerId = $null
$env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
try {
    $containerId = (& docker run -d --rm --pull=never --name $name `
            --label "financial-helper.registry-sql-test.run=$run" `
            --tmpfs '/var/lib/postgresql/data:rw,nosuid,size=128m' `
            -e POSTGRES_PASSWORD postgres:16-alpine).Trim()
    if ($LASTEXITCODE -ne 0 -or $containerId -notmatch '^[a-f0-9]{64}$') { throw 'Test container creation failed' }
    $container = (& docker inspect $containerId | ConvertFrom-Json)[0]
    if ($container.Id -ne $containerId -or $container.Name -ne "/$name" -or
            $container.Config.Labels.'financial-helper.registry-sql-test.run' -ne $run -or
            -not $container.HostConfig.Tmpfs.'/var/lib/postgresql/data' -or
            $container.Mounts.Count -ne 0) { throw 'Test container identity mismatch' }
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec $containerId pg_isready -U postgres *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    if ($LASTEXITCODE -ne 0) { throw 'Test PostgreSQL is not ready' }
    $schema = 'CREATE TABLE source_registry (id uuid, source_key text UNIQUE, organization_name text, official_domain text, canonical_url text, acquisition_type text, enabled boolean, created_at timestamptz, updated_at timestamptz)'
    & docker exec $containerId psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c $schema *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Test schema creation failed' }
    $file = Join-Path $PSScriptRoot 'official-demo-source-registry.sql'
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Registry SQL file is missing' }
    & docker cp $file "${containerId}:/tmp/official-demo-source-registry.sql" *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Registry SQL copy failed' }
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        & docker exec $containerId psql -U postgres -d postgres -v ON_ERROR_STOP=1 `
                -f /tmp/official-demo-source-registry.sql *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Registry SQL execution failed' }
    }
    $query = "SELECT count(*) || '|' || max(encode(convert_to(organization_name,'UTF8'),'hex')) || '|' || bool_and(canonical_url='https://www.fsc.go.kr/comm/getFile?srvcId=BBSTY1&upperNo=84975&fileTy=ATTACH&fileNo=1') FROM source_registry"
    $result = (& docker exec $containerId psql -U postgres -d postgres -tA `
            -v ON_ERROR_STOP=1 -c $query).Trim()
    if ($LASTEXITCODE -ne 0 -or $result -ne '1|eab888ec9cb5ec9c84ec9b90ed9a8c|true') {
        throw "Registry SQL UTF-8, URL, or idempotency check failed: $result"
    }
    Write-Output 'REGISTRY_SQL_PASS utf8=verified url=verified idempotent=verified'
} finally {
    if ($containerId -match '^[a-f0-9]{64}$') {
        $container = (& docker inspect $containerId | ConvertFrom-Json)[0]
        if ($container.Id -eq $containerId -and $container.Name -eq "/$name" -and
                $container.Config.Labels.'financial-helper.registry-sql-test.run' -eq $run) {
            & docker rm -f $containerId *> $null
            if ($LASTEXITCODE -ne 0) { throw 'Owned test container cleanup failed' }
        } else { throw 'Test container ownership changed; no container was removed' }
    }
    Remove-Item Env:POSTGRES_PASSWORD -ErrorAction SilentlyContinue
}
