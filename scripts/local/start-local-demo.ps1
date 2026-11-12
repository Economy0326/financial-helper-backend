param([string]$FrontendDirectory = 'C:\Users\economy\Desktop\dev\financial-helper-frontend')
$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'fh-official-local-demo'
$volumeName = 'fh-official-local-demo-data'
$dbName = 'fh_official_local_demo'
$dbUser = 'fh_official_demo'
$dbPort = 54765
$kurePort = 18091
$createdContainer = $false
$createdVolume = $false
$containerStartedHere = $false
$backendProcess = $null
$frontendProcess = $null
$kureProcess = $null
$ownedLogs = @()
function Inspect-Json([string[]]$arguments) {
    $ErrorActionPreference = 'Continue'
    $json = & docker @arguments 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $json) { return $null }
    return ($json | ConvertFrom-Json)[0]
}
function Assert-PortFree([int]$port) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
    try { $listener.Start() } finally { $listener.Stop() }
}
try {
    foreach ($variable in @('DB_URL','DB_USERNAME','DB_PASSWORD','TEST_DB_URL','TEST_DB_PASSWORD',
            'E2E_DB_URL','E2E_DB_PASSWORD','SPRING_DATASOURCE_URL','FH_DEMO_DB_URL',
            'FH_DEMO_DB_USERNAME','FH_DEMO_DB_PASSWORD')) {
        if ([Environment]::GetEnvironmentVariable($variable)) { throw "Existing database variable $variable forbids demo startup" }
    }
    $oauthUris = @{
        KAKAO_REDIRECT_URI='http://localhost:8080/api/v1/auth/kakao/callback'
        KAKAO_FRONTEND_SUCCESS_URI='http://localhost:3000/account'
        NAVER_REDIRECT_URI='http://localhost:8080/api/v1/auth/naver/callback'
        NAVER_FRONTEND_SUCCESS_URI='http://localhost:3000/account'
    }
    foreach ($key in $oauthUris.Keys) {
        $value = [Environment]::GetEnvironmentVariable($key)
        if ($value -and $value -ne $oauthUris[$key]) { throw "OAuth URI $key is incompatible with the localhost demo" }
    }
    foreach ($port in @($dbPort, $kurePort, 8080, 3000)) { Assert-PortFree $port }
    & docker image inspect postgres:16-alpine --format '{{.Id}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Local postgres:16-alpine image is required' }
    $volume = Inspect-Json -arguments @('volume','inspect',$volumeName)
    $container = Inspect-Json -arguments @('inspect',$name)
    if (-not $volume -and $container) { throw 'Container exists without verified volume ownership' }
    if ($volume -and -not $container) {
        throw 'Persistent volume exists without its owned container; manual recovery is required'
    }
    if (-not $volume) {
        $owner = [Guid]::NewGuid().ToString('N')
        & docker volume create --label "financial-helper.official-local-demo.owner=$owner" $volumeName *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Dedicated volume creation failed' }
        $createdVolume = $true
        $volume = Inspect-Json -arguments @('volume','inspect',$volumeName)
    }
    $owner = $volume.Labels.'financial-helper.official-local-demo.owner'
    if ($volume.Name -ne $volumeName -or $owner -notmatch '^[a-f0-9]{32}$' -or
            $volume.Driver -ne 'local') { throw 'Dedicated volume ownership cannot be verified' }
    if (-not $container) {
        $password = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
        $env:POSTGRES_DB = $dbName
        $env:POSTGRES_USER = $dbUser
        $env:POSTGRES_PASSWORD = $password
        $id = (& docker run -d --pull=never --name $name `
                --label "financial-helper.official-local-demo.owner=$owner" `
                --mount "type=volume,source=$volumeName,target=/var/lib/postgresql/data" `
                -p "127.0.0.1:${dbPort}:5432" -e POSTGRES_DB -e POSTGRES_USER -e POSTGRES_PASSWORD `
                postgres:16-alpine).Trim()
        if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[a-f0-9]{64}$') { throw 'Dedicated container creation failed' }
        $createdContainer = $true
        $containerStartedHere = $true
        $container = Inspect-Json -arguments @('inspect',$id)
    } else {
        if ($container.State.Running) { throw 'Dedicated demo container is already running; no resource was changed' }
        $id = $container.Id
    }
    $mount = @($container.Mounts)
    $binding = @($container.HostConfig.PortBindings.'5432/tcp')
    if ($container.Id -ne $id -or $container.Name -ne "/$name" -or
            $container.Config.Labels.'financial-helper.official-local-demo.owner' -ne $owner -or
            $container.Config.Image -ne 'postgres:16-alpine' -or $mount.Count -ne 1 -or
            $mount[0].Type -ne 'volume' -or $mount[0].Name -ne $volumeName -or
            $mount[0].Destination -ne '/var/lib/postgresql/data' -or
            $binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or
            $binding[0].HostPort -ne "$dbPort" -or $container.HostConfig.Tmpfs) {
        throw 'Dedicated container identity or storage mismatch'
    }
    $vars = @($container.Config.Env)
    $passwordEntry = @($vars | Where-Object { $_ -like 'POSTGRES_PASSWORD=*' })
    if ($passwordEntry.Count -ne 1 -or $passwordEntry[0].Substring(18).Length -lt 24 -or
            $vars -notcontains "POSTGRES_DB=$dbName" -or $vars -notcontains "POSTGRES_USER=$dbUser") {
        throw 'Dedicated database credentials or identity mismatch'
    }
    if (-not $containerStartedHere) {
        & docker start $id *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Dedicated container restart failed' }
        $containerStartedHere = $true
    }
    $env:FH_DEMO_STORAGE_MODE = 'persistent'
    $env:FH_DEMO_OWNER_ID = $owner
    $env:FH_DEMO_CONTAINER_ID = $id
    $env:FH_DEMO_DB_URL = "jdbc:postgresql://127.0.0.1:$dbPort/$dbName"
    $env:FH_DEMO_DB_USERNAME = $dbUser
    $env:FH_DEMO_DB_PASSWORD = $passwordEntry[0].Substring(18)
    $env:SPRING_PROFILES_ACTIVE = 'official-local-demo'
    $env:AI_ENABLED = 'false'
    $env:LAW_OPEN_API_ENABLED = 'false'
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        & docker exec $id pg_isready -U $dbUser -d $dbName *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated PostgreSQL is not ready' }
    $indexRoot = Join-Path $env:LOCALAPPDATA 'FinancialHelper\official-local-demo-index'
    $marker = Join-Path $indexRoot 'owner.id'
    if (Test-Path $indexRoot) {
        if (-not (Test-Path $marker) -or (Get-Content $marker -Raw).Trim() -ne $owner) {
            throw 'Persistent KURE index owner mismatch'
        }
    } else {
        New-Item -ItemType Directory -Path $indexRoot -Force | Out-Null
        [System.IO.File]::WriteAllText($marker, $owner)
    }
    $kureOut = [System.IO.Path]::GetTempFileName(); $kureErr = [System.IO.Path]::GetTempFileName()
    $ownedLogs += $kureOut,$kureErr
    $kureProcess = Start-Process -FilePath (Get-Command python.exe).Source `
            -ArgumentList @('retrieval_runtime/runtime.py','--host','127.0.0.1','--port',"$kurePort",'--index-root',$indexRoot) `
            -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput $kureOut -RedirectStandardError $kureErr -PassThru
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($kureProcess.HasExited) { throw 'Local KURE runtime exited' }
        try { if ((Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$kurePort/v1/runtime" -TimeoutSec 2).StatusCode -eq 200) { break } } catch {}
        Start-Sleep -Seconds 1
    }
    if ($attempt -ge 60) { throw 'Local KURE runtime not ready' }
    $env:KURE_RUNTIME_ENABLED = 'true'
    $env:KURE_RUNTIME_ENDPOINT = "http://127.0.0.1:$kurePort"
    Push-Location $backend
    try { & .\gradlew.bat bootJar --offline --no-daemon *> $null; if ($LASTEXITCODE -ne 0) { throw 'Backend bootJar failed' } }
    finally { Pop-Location }
    $backendOut = [System.IO.Path]::GetTempFileName(); $backendErr = [System.IO.Path]::GetTempFileName()
    $ownedLogs += $backendOut,$backendErr
    $jar = Join-Path $backend 'build/libs/financial-helper-backend-0.0.1-SNAPSHOT.jar'
    $backendProcess = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar',$jar) `
            -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr -PassThru
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($backendProcess.HasExited) { throw 'Backend exited before readiness' }
        try { if ((Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8080/health' -TimeoutSec 2).StatusCode -eq 200) { break } } catch {}
        Start-Sleep -Seconds 1
    }
    if ($attempt -ge 90) { throw 'Backend not ready' }
    $registrySql = Join-Path $PSScriptRoot 'official-demo-source-registry.sql'
    & docker cp $registrySql "${id}:/tmp/official-demo-source-registry.sql" *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Candidate registry SQL copy failed' }
    & docker exec $id psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1 -f /tmp/official-demo-source-registry.sql *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Candidate registry registration failed' }
    $FrontendDirectory = (Resolve-Path -LiteralPath $FrontendDirectory).Path
    $env:NEXT_PUBLIC_API_BASE_URL = 'http://localhost:8080/api/v1'
    $env:NEXT_PUBLIC_OFFICIAL_REVIEW_ENABLED = 'true'
    Push-Location $FrontendDirectory
    try { & npm run build *> $null; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' } }
    finally { Pop-Location }
    $frontendOut = [System.IO.Path]::GetTempFileName(); $frontendErr = [System.IO.Path]::GetTempFileName()
    $ownedLogs += $frontendOut,$frontendErr
    $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source `
            -ArgumentList @('node_modules/next/dist/bin/next','start','--hostname','127.0.0.1','--port','3000') `
            -WorkingDirectory $FrontendDirectory -WindowStyle Hidden -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr -PassThru
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($frontendProcess.HasExited) { throw 'Frontend exited before readiness' }
        try { if ((Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:3000/official-source-review/deposit' -TimeoutSec 2).StatusCode -eq 200) { break } } catch {}
        Start-Sleep -Seconds 1
    }
    if ($attempt -ge 30) { throw 'Reviewer page is not ready' }
    $generations = & docker exec $id psql -U $dbUser -d $dbName -tA -v ON_ERROR_STOP=1 `
            -c 'SELECT a.retrieval_generation_id::text || ''|'' || g.corpus_snapshot_sha256 || ''|'' || g.status || ''|'' || g.model_revision FROM active_retrieval_generation a JOIN retrieval_generation g ON g.id=a.retrieval_generation_id'
    if ($LASTEXITCODE -ne 0) { throw 'Active generation check failed' }
    foreach ($entry in @($generations)) {
        if (-not $entry) { continue }
        $parts = $entry.Split('|')
        if ($parts.Count -ne 4 -or $parts[0] -notmatch '^[a-f0-9-]{36}$' -or
                $parts[1] -notmatch '^[a-f0-9]{64}$' -or $parts[2] -ne 'READY' -or
                $parts[3] -ne '3431f86d399d666083890dbb882aced6708873bc') {
            throw 'Active generation metadata is invalid or not READY'
        }
        $manifestPath = Join-Path $indexRoot ($parts[0] + '/manifest.json')
        if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'Active generation KURE artifact is missing' }
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if ($manifest.generationId -ne $parts[0] -or $manifest.corpusSnapshotSha256 -ne $parts[1] -or
                $manifest.modelRevision -ne '3431f86d399d666083890dbb882aced6708873bc') {
            throw 'Active generation KURE artifact does not match database'
        }
        $readiness = Invoke-RestMethod -Uri "http://127.0.0.1:$kurePort/v1/generations/$($parts[0])/readiness" -TimeoutSec 10
        if ($readiness.generationId -ne $parts[0]) { throw 'Active generation runtime readiness mismatch' }
    }
    Write-Output "PERSISTENT_LOCAL_DEMO_READY container=$id db=$dbName port=$dbPort owner=$owner"
    Write-Output 'The database and KURE index persist after exit. No reviewer grant, approval, or READY was created.'
    Read-Host 'Press Enter to stop this demo application and its verified container; data will persist'
} finally {
    foreach ($process in @($frontendProcess,$backendProcess,$kureProcess)) {
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    }
    if ($containerStartedHere -and $id -match '^[a-f0-9]{64}$') {
        $current = Inspect-Json -arguments @('inspect',$id)
        if ($current -and $current.Id -eq $id -and $current.Name -eq "/$name" -and
                $current.Config.Labels.'financial-helper.official-local-demo.owner' -eq $owner) {
            & docker stop $id *> $null
        }
    }
    if ($createdVolume -and -not $createdContainer) {
        $currentVolume = Inspect-Json -arguments @('volume','inspect',$volumeName)
        $currentContainer = Inspect-Json -arguments @('inspect',$name)
        if ($currentVolume -and -not $currentContainer -and $currentVolume.Name -eq $volumeName -and
                $currentVolume.Labels.'financial-helper.official-local-demo.owner' -eq $owner) {
            & docker volume rm $volumeName *> $null
        }
    }
    foreach ($file in $ownedLogs) { if (Test-Path $file) { Remove-Item -LiteralPath $file -Force } }
    foreach ($variable in @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','FH_DEMO_STORAGE_MODE',
            'FH_DEMO_OWNER_ID','FH_DEMO_CONTAINER_ID','FH_DEMO_DB_URL','FH_DEMO_DB_USERNAME',
            'FH_DEMO_DB_PASSWORD','SPRING_PROFILES_ACTIVE','AI_ENABLED','LAW_OPEN_API_ENABLED',
            'KURE_RUNTIME_ENABLED','KURE_RUNTIME_ENDPOINT','NEXT_PUBLIC_API_BASE_URL',
            'NEXT_PUBLIC_OFFICIAL_REVIEW_ENABLED')) {
        Remove-Item "Env:$variable" -ErrorAction SilentlyContinue
    }
}
