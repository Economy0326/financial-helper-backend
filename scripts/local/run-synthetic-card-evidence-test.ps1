param([switch]$FullSuite)
$ErrorActionPreference = 'Stop'
$run = [guid]::NewGuid().ToString('N')
$name = 'fh-isolated-' + $run
$env:POSTGRES_DB = 'fh_test_' + $run
$env:POSTGRES_USER = 'fh_user_' + $run.Substring(0, 12)
$env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$id = $null
$result = 1
try {
    docker info --format '{{.OSType}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable' }
    docker image inspect postgres:16-alpine --format '{{.Id}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Local postgres:16-alpine image is unavailable; no pull was attempted' }
    $id = (docker run -d --rm --pull never --name $name --label "financial-helper.test-run=$run" `
        --tmpfs '/var/lib/postgresql/data:rw,noexec,nosuid,size=256m' -p '127.0.0.1::5432' `
        -e POSTGRES_DB -e POSTGRES_USER -e POSTGRES_PASSWORD postgres:16-alpine -c max_connections=300).Trim()
    if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[a-f0-9]{64}$') { throw 'Container creation failed' }
    $container = (docker inspect $id | ConvertFrom-Json)[0]
    $binding = $container.NetworkSettings.Ports.'5432/tcp'
    if ($container.Id -ne $id -or $container.Name -ne ('/' + $name) -or
        $container.Config.Labels.'financial-helper.test-run' -ne $run -or
        $binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or
        -not $container.HostConfig.Tmpfs.'/var/lib/postgresql/data' -or $container.Mounts.Count -ne 0) {
        throw 'Container isolation verification failed'
    }
    $env:FH_TEST_RUN_ID = $run
    $env:FH_TEST_CONTAINER_ID = $id
    $env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:' + $binding[0].HostPort + '/' + $env:POSTGRES_DB
    $env:TEST_DB_USERNAME = $env:POSTGRES_USER
    $env:TEST_DB_PASSWORD = $env:POSTGRES_PASSWORD
    Write-Output "Synthetic test container: $id, $name, 127.0.0.1:$($binding[0].HostPort); tmpfs verified"
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        docker exec $id pg_isready -U $env:POSTGRES_USER -d $env:POSTGRES_DB *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Temporary PostgreSQL did not become ready' }
    Push-Location (Join-Path $PSScriptRoot '../..')
    try {
        if ($FullSuite) {
            & .\gradlew.bat test --no-daemon
            $result = $LASTEXITCODE
            if ($result -eq 0) {
                & .\gradlew.bat build --no-daemon
                $result = $LASTEXITCODE
            }
        } else {
            & .\gradlew.bat test --no-daemon --rerun-tasks `
                --tests 'com.financialhelper.retrieval.OfficialInformationCorpusPersistenceIntegrationTest.syntheticCommonCardReportWorksWithoutKnownInstitutionAndRevocationBlocksReload' `
                --tests 'com.financialhelper.retrieval.GoldenCoverageEvaluationTest'
            $result = $LASTEXITCODE
        }
    } finally { Pop-Location }
} finally {
    if ($id -and $id -match '^[a-f0-9]{64}$') {
        $current = (docker inspect $id 2>$null | ConvertFrom-Json)[0]
        if ($current -and $current.Id -eq $id -and $current.Name -eq ('/' + $name) -and
            $current.Config.Labels.'financial-helper.test-run' -eq $run) {
            docker rm -f $id | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-Warning 'Temporary test container removal failed'
                $result = 1
            } else {
                Write-Output 'Temporary test container removed'
            }
        } else {
            Write-Warning 'Container identity mismatch; no container was removed'
            $result = 1
        }
    }
    Remove-Item Env:POSTGRES_PASSWORD, Env:TEST_DB_PASSWORD, Env:FH_TEST_RUN_ID, Env:FH_TEST_CONTAINER_ID -ErrorAction SilentlyContinue
}
exit $result
