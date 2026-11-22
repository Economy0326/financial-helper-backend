param([ValidateSet('Start','Stop','Grant')][string]$Action='Start', [guid]$AccountId, [switch]$EnableDocumentAi)
$ErrorActionPreference='Stop'
if ($MyInvocation.InvocationName -eq '.') { throw 'Run this script in a separate PowerShell process' }
if ($env:FH_SEPARATE_REVIEW_CHILD -ne '1') {
    $launch=[Diagnostics.ProcessStartInfo]::new()
    $launch.FileName=(Get-Command powershell.exe).Source
    $launch.Arguments='-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -Action ' + $Action
    if ($EnableDocumentAi) { $launch.Arguments += ' -EnableDocumentAi' }
    if ($PSBoundParameters.ContainsKey('AccountId')) { $launch.Arguments += ' -AccountId ' + $AccountId.ToString() }
    $launch.UseShellExecute=$false; $launch.CreateNoWindow=$true
    $launch.EnvironmentVariables['FH_SEPARATE_REVIEW_CHILD']='1'
    $child=[Diagnostics.Process]::Start($launch)
    $child.WaitForExit()
    if ($child.ExitCode -ne 0) { throw 'Separate review child process failed' }
    return
}
$backend=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$frontend=(Resolve-Path (Join-Path $backend '../financial-helper-frontend')).Path
$name='fh-official-review-mvp'; $volumeName='fh-official-review-mvp-data'; $label='financial-helper.official-review-mvp.owner'
$db='fh_review_demo'; $dbUser='fh_review_demo'; $dbPort=15439; $backendPort=18080; $frontendPort=13000; $kurePort=18092
$base=Join-Path $backend 'build/separate-review-demo'; $statePath=Join-Path $base 'state.json'
function Inspect([string[]]$arguments) {
    $prior=$ErrorActionPreference; $ErrorActionPreference='Continue'
    try { $json=& docker @arguments 2>$null; if ($LASTEXITCODE -ne 0 -or -not $json) { return $null }; return ($json | ConvertFrom-Json)[0] }
    finally { $ErrorActionPreference=$prior }
}
function CheckOwned($container,$volume) {
    if (-not $container -or -not $volume) { throw 'Separate review resources not found' }
    $owner=$volume.Labels.$label; $mounts=@($container.Mounts); $ports=@($container.HostConfig.PortBindings.'5432/tcp')
    if ($owner -notmatch '^[a-f0-9]{32}$' -or $volume.Name -ne $volumeName -or $volume.Driver -ne 'local' -or
        $container.Name -ne ('/' + $name) -or $container.Config.Image -ne 'postgres:16-alpine' -or
        $container.Config.Labels.$label -ne $owner -or $mounts.Count -ne 1 -or $mounts[0].Type -ne 'volume' -or
        $mounts[0].Name -ne $volumeName -or $mounts[0].Destination -ne '/var/lib/postgresql/data' -or
        $ports.Count -ne 1 -or $ports[0].HostIp -ne '127.0.0.1' -or $ports[0].HostPort -ne "$dbPort") {
        throw 'Separate review ownership verification failed; no resource was changed'
    }
    return $owner
}
function CheckPort([int]$port) { $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port); try { $listener.Start() } finally { $listener.Stop() } }
function StopOwned($entry,[string]$marker) {
    if (-not $entry) { return }
    $process=Get-Process -Id ([int]$entry.id) -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq [string]$entry.started) {
        $info=Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$entry.id)
        if (-not $info.CommandLine -or -not $info.CommandLine.Contains($marker)) { throw 'Process ownership verification failed' }
        Stop-Process -Id $process.Id
    }
}
$container=Inspect @('inspect',$name); $volume=Inspect @('volume','inspect',$volumeName)
if ($Action -ne 'Start') {
    $owner=CheckOwned $container $volume
    if ($Action -eq 'Grant') {
        if (-not $PSBoundParameters.ContainsKey('AccountId') -or $AccountId -eq [guid]::Empty -or -not $container.State.Running) { throw 'A valid logged-in account and running review DB are required' }
        $account=$AccountId.ToString()
        $sql="INSERT INTO source_reviewer_grant(account_id,granted_by) SELECT id,'separate-review-owner' FROM account WHERE id='$account' AND status='ACTIVE' ON CONFLICT (account_id) DO NOTHING"
        & docker exec $container.Id psql -U $dbUser -d $db -v ON_ERROR_STOP=1 -c $sql *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Reviewer grant failed' }
        $count=(& docker exec $container.Id psql -U $dbUser -d $db -tA -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM source_reviewer_grant WHERE account_id='$account' AND revoked_at IS NULL").Trim()
        if ($LASTEXITCODE -ne 0 -or $count -ne '1') { throw 'Active reviewer grant was not verified' }
        Write-Output 'Reviewer access granted in the separate review database.'; exit 0
    }
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'Separate review process state not found' }
    $state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.owner -ne $owner -or $state.containerId -ne $container.Id) { throw 'Separate review state mismatch' }
    StopOwned $state.frontend $state.session; StopOwned $state.backend $owner; StopOwned $state.kure 'separate-review-demo'
    if ($container.State.Running) { & docker stop $container.Id *> $null; if ($LASTEXITCODE -ne 0) { throw 'Separate review DB stop failed' } }
    Write-Output 'Separate review services stopped. The DB volume and index remain.'; exit 0
}
foreach ($port in @($dbPort,$backendPort,$frontendPort,$kurePort)) { CheckPort $port }
if (-not (($env:KAKAO_LOGIN_ENABLED -eq 'true' -and $env:KAKAO_CLIENT_ID -and $env:KAKAO_CLIENT_SECRET) -or
          ($env:NAVER_LOGIN_ENABLED -eq 'true' -and $env:NAVER_CLIENT_ID -and $env:NAVER_CLIENT_SECRET))) { throw 'An existing enabled OAuth provider is required' }
if ($EnableDocumentAi -and [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) { throw 'OPENAI_API_KEY is required for document AI' }
& docker image inspect postgres:16-alpine --format '{{.Id}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Local postgres:16-alpine image is required' }
if (($container -and -not $volume) -or ($volume -and -not $container)) { throw 'Incomplete separate resources require manual recovery' }
$newBase = -not (Test-Path -LiteralPath $base)
if ($newBase) {
    New-Item -ItemType Directory -Path $base | Out-Null
    $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=[System.Security.AccessControl.DirectorySecurity]::new(); $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false)
    $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    Set-Acl -LiteralPath $base -AclObject $acl
}
if (-not $newBase -and -not (Test-Path -LiteralPath (Join-Path $base 'owner.id') -PathType Leaf)) { throw 'Existing review directory lacks an ownership marker' }
if (($newBase -and $volume) -or (-not $newBase -and -not $volume)) { throw 'Review DB and private index directory must both exist or both be new' }
if ($volume) {
    $existingOwner=CheckOwned $container $volume
    if ((Get-Content -LiteralPath (Join-Path $base 'owner.id') -Raw).Trim() -ne $existingOwner) { throw 'Review DB and directory ownership mismatch' }
}
if (-not $volume) {
    $owner=[guid]::NewGuid().ToString('N')
    & docker volume create --label "$label=$owner" $volumeName *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Separate review volume creation failed' }
    $env:POSTGRES_DB=$db; $env:POSTGRES_USER=$dbUser; $env:POSTGRES_PASSWORD=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
    $id=(& docker run -d --pull=never --name $name --label "$label=$owner" --mount "type=volume,source=$volumeName,target=/var/lib/postgresql/data" `
        -p "127.0.0.1:${dbPort}:5432" -e POSTGRES_DB -e POSTGRES_USER -e POSTGRES_PASSWORD postgres:16-alpine).Trim()
    if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[a-f0-9]{64}$') { throw 'Separate DB creation failed; volume preserved' }
    $container=Inspect @('inspect',$id); $volume=Inspect @('volume','inspect',$volumeName)
} else {
    $owner=CheckOwned $container $volume
    if ($container.State.Running) { throw 'Separate review DB is already running; no resource changed' }
    $id=$container.Id; & docker start $id *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Separate DB start failed' }
    $container=Inspect @('inspect',$id)
}
$owner=CheckOwned $container $volume
$ownerFile=Join-Path $base 'owner.id'
if ($newBase) { [IO.File]::WriteAllText($ownerFile,$owner) }
elseif ((Get-Content -LiteralPath $ownerFile -Raw).Trim() -ne $owner) { throw 'Review directory and DB ownership mismatch' }
$passwords=@($container.Config.Env | Where-Object { $_ -like 'POSTGRES_PASSWORD=*' })
if ($passwords.Count -ne 1 -or $passwords[0].Substring(18).Length -lt 24 -or
    $container.Config.Env -notcontains "POSTGRES_DB=$db" -or $container.Config.Env -notcontains "POSTGRES_USER=$dbUser") {
    & docker stop $id *> $null
    throw 'Separate DB identity verification failed; volume preserved'
}
# Remove inherited DB selectors only from this short-lived launch process; the parent environment is untouched.
foreach ($key in @('DB_URL','DB_USERNAME','DB_PASSWORD','DATABASE_URL','TEST_DB_URL','TEST_DB_PASSWORD','E2E_DB_URL','E2E_DB_PASSWORD',
        'POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD',
        'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','SPRING_FLYWAY_URL','SPRING_FLYWAY_USER','SPRING_FLYWAY_PASSWORD',
        'SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION','SPRING_CONFIG_IMPORT','SPRING_CONFIG_NAME',
        'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','PORT','SERVER_ADDRESS')) { [Environment]::SetEnvironmentVariable($key,$null,'Process') }
$env:FH_DEMO_STORAGE_MODE='separate-review'; $env:FH_DEMO_OWNER_ID=$owner; $env:FH_DEMO_CONTAINER_ID=$id
$env:FH_DEMO_DB_URL="jdbc:postgresql://127.0.0.1:$dbPort/$db"; $env:FH_DEMO_DB_USERNAME=$dbUser; $env:FH_DEMO_DB_PASSWORD=$passwords[0].Substring(18)
$backendSecrets=@{}
foreach($key in @('FH_DEMO_DB_PASSWORD','KAKAO_CLIENT_ID','KAKAO_CLIENT_SECRET','NAVER_CLIENT_ID','NAVER_CLIENT_SECRET','OPENAI_API_KEY')) {
    $backendSecrets[$key]=[Environment]::GetEnvironmentVariable($key)
    [Environment]::SetEnvironmentVariable($key,$null,'Process')
}
if (-not $EnableDocumentAi) { $backendSecrets['OPENAI_API_KEY']=$null }
foreach($key in @('OPENAI_API_KEY','APP_AI_OPENAI_API_KEY','APP_AI_DOCUMENT_EXPLANATIONS_ENABLED','APP_AI_DOCUMENT_SEMANTIC_VERIFICATION_ENABLED')) { [Environment]::SetEnvironmentVariable($key,$null,'Process') }
$env:SPRING_PROFILES_ACTIVE='official-demo'; $env:AI_ENABLED='false'; $env:LAW_OPEN_API_ENABLED='false'; $env:SOURCE_INGESTION_RUN_ON_STARTUP='false'
$env:APP_AI_DOCUMENT_EXPLANATIONS_ENABLED=if ($EnableDocumentAi) {'true'} else {'false'}
$env:APP_AI_DOCUMENT_SEMANTIC_VERIFICATION_ENABLED=if ($EnableDocumentAi) {'true'} else {'false'}
$env:HF_HUB_OFFLINE='1'; $env:TRANSFORMERS_OFFLINE='1'; $env:KURE_DEVICE='cpu'
$env:KURE_MODEL_PATH=$null; $env:KURE_INDEX_ROOT=$null
$env:KAKAO_REDIRECT_URI="http://localhost:$backendPort/api/v1/auth/kakao/callback"; $env:NAVER_REDIRECT_URI="http://localhost:$backendPort/api/v1/auth/naver/callback"
$env:KAKAO_FRONTEND_SUCCESS_URI="http://localhost:$frontendPort/account"; $env:NAVER_FRONTEND_SUCCESS_URI="http://localhost:$frontendPort/account"
$session=[guid]::NewGuid().ToString('N'); $sessionDir=Join-Path $base $session; New-Item -ItemType Directory -Path $sessionDir | Out-Null
$indexRoot=Join-Path $base 'kure-index'; if (-not (Test-Path -LiteralPath $indexRoot)) { New-Item -ItemType Directory -Path $indexRoot | Out-Null }
$state=@{ owner=$owner; containerId=$id; session=$session; backend=$null; frontend=$null; kure=$null }
function Record([string]$key,$process) { $state[$key]=@{id=$process.Id;started=$process.StartTime.ToUniversalTime().Ticks.ToString()}; $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8 }
try {
    for($i=0;$i -lt 40;$i++){ & docker exec $id pg_isready -U $dbUser -d $db *> $null; if($LASTEXITCODE -eq 0){break}; Start-Sleep -Seconds 1 }
    if($LASTEXITCODE -ne 0){throw 'Separate DB not ready'}
    # Build sequentially before loading KURE to limit peak memory use.
    Push-Location $backend; try{& .\gradlew.bat bootJar --offline --no-daemon *> $null; if($LASTEXITCODE -ne 0){throw 'Backend build failed'}}finally{Pop-Location}
    # Ports are verified free before startup, so this private build is not in use.
    # Reuse the same output and derived config instead of leaving one per session.
    $dist='.next-separate-review'; $env:FH_UI_TEST_DIST_DIR=$dist; $tsconfig=Join-Path $frontend ($dist+'.tsconfig.json')
    Copy-Item -LiteralPath (Join-Path $frontend 'tsconfig.json') -Destination $tsconfig -Force
    $env:NEXT_PUBLIC_API_BASE_URL="http://localhost:$backendPort/api/v1"; $env:NEXT_PUBLIC_OFFICIAL_REVIEW_ENABLED='true'; $env:NEXT_TELEMETRY_DISABLED='1'
    Push-Location $frontend; try{& npm run build *> $null;if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}}finally{Pop-Location}
    $kure=Start-Process -FilePath (Get-Command python.exe).Source -ArgumentList @('retrieval_runtime/runtime.py','--host','127.0.0.1','--port',"$kurePort",'--index-root',('"'+$indexRoot+'"')) -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $sessionDir 'kure.out') -RedirectStandardError (Join-Path $sessionDir 'kure.err') -PassThru
    Record 'kure' $kure
    for($i=0;$i -lt 120;$i++){if($kure.HasExited){throw 'KURE exited'};try{if((Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$kurePort/v1/runtime" -TimeoutSec 2).StatusCode -eq 200){break}}catch{};Start-Sleep -Seconds 1}
    if($i -ge 120){throw 'KURE not ready'}
    $env:KURE_RUNTIME_ENABLED='true'; $env:KURE_RUNTIME_ENDPOINT="http://127.0.0.1:$kurePort"
    $jar=Join-Path $backend 'build/libs/financial-helper-backend-0.0.1-SNAPSHOT.jar'
    foreach($key in $backendSecrets.Keys) { [Environment]::SetEnvironmentVariable($key,$backendSecrets[$key],'Process') }
    $env:SPRING_DATASOURCE_PASSWORD=$backendSecrets['FH_DEMO_DB_PASSWORD']; $env:SPRING_FLYWAY_PASSWORD=$backendSecrets['FH_DEMO_DB_PASSWORD']
    $app=Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar',('"'+$jar+'"'),'--spring.profiles.active=official-demo','--spring.config.location=classpath:/application.yml,classpath:/application-official-demo.yml',"--spring.datasource.url=$($env:FH_DEMO_DB_URL)","--spring.datasource.username=$dbUser",'--spring.datasource.driver-class-name=org.postgresql.Driver',"--spring.flyway.url=$($env:FH_DEMO_DB_URL)","--spring.flyway.user=$dbUser",'--server.address=127.0.0.1',"--server.port=$backendPort","--app.cors.allowed-origin=http://localhost:$frontendPort","--app.separate-review-owner=$owner") -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $sessionDir 'backend.out') -RedirectStandardError (Join-Path $sessionDir 'backend.err') -PassThru
    Remove-Item Env:SPRING_DATASOURCE_PASSWORD,Env:SPRING_FLYWAY_PASSWORD -ErrorAction SilentlyContinue
    foreach($key in $backendSecrets.Keys) { [Environment]::SetEnvironmentVariable($key,$null,'Process') }
    Record 'backend' $app
    for($i=0;$i -lt 120;$i++){if($app.HasExited){throw 'Backend exited'};try{if((Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$backendPort/health" -TimeoutSec 2).StatusCode -eq 200){break}}catch{};Start-Sleep -Seconds 1}
    if($i -ge 120){throw 'Backend not ready'}
    $generations=& docker exec $id psql -U $dbUser -d $db -tA -v ON_ERROR_STOP=1 -c 'SELECT a.retrieval_generation_id::text || ''|'' || g.corpus_snapshot_sha256 || ''|'' || g.status || ''|'' || g.model_revision FROM active_retrieval_generation a JOIN retrieval_generation g ON g.id=a.retrieval_generation_id'
    if($LASTEXITCODE -ne 0){throw 'Separate active generation check failed'}
    foreach($entry in @($generations)) {
        if(-not $entry){continue}; $parts=$entry.Split('|')
        if($parts.Count -ne 4 -or $parts[0] -notmatch '^[a-f0-9-]{36}$' -or $parts[1] -notmatch '^[a-f0-9]{64}$' -or $parts[2] -ne 'READY' -or $parts[3] -ne '3431f86d399d666083890dbb882aced6708873bc'){throw 'Separate active generation metadata invalid'}
        $manifestPath=Join-Path $indexRoot ($parts[0]+'/manifest.json')
        if(-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)){throw 'Separate active generation index missing'}
        $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if($manifest.generationId -ne $parts[0] -or $manifest.corpusSnapshotSha256 -ne $parts[1] -or $manifest.modelRevision -ne $parts[3]){throw 'Separate active generation index mismatch'}
        $ready=Invoke-RestMethod -Uri "http://127.0.0.1:$kurePort/v1/generations/$($parts[0])/readiness" -TimeoutSec 10
        if($ready.generationId -ne $parts[0] -or -not $ready.ready){throw 'Separate active generation not ready'}
    }
    $launcher=Join-Path $sessionDir 'next-launch.cjs'; $env:FH_REVIEW_NEXT_CLI=Join-Path $frontend 'node_modules/next/dist/bin/next'
    [IO.File]::WriteAllText($launcher,"process.argv=[process.execPath,'next','start','--hostname','127.0.0.1','--port','$frontendPort'];require(process.env.FH_REVIEW_NEXT_CLI);")
    $ui=Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @(('"'+$launcher+'"')) -WorkingDirectory $frontend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $sessionDir 'frontend.out') -RedirectStandardError (Join-Path $sessionDir 'frontend.err') -PassThru
    Record 'frontend' $ui
    for($i=0;$i -lt 60;$i++){if($ui.HasExited){throw 'Frontend exited'};try{if((Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$frontendPort/official-source-review/documents" -TimeoutSec 2).StatusCode -eq 200){break}}catch{};Start-Sleep -Seconds 1}
    if($i -ge 60){throw 'Frontend not ready'}
    Write-Output "Separate review frontend: http://localhost:$frontendPort/official-source-review/documents"
    Write-Output 'Database and index persist. No document rights or Claim approvals were created.'
} catch {
    try { StopOwned $state.frontend $session } catch {}
    try { StopOwned $state.backend $owner } catch {}
    try { StopOwned $state.kure 'separate-review-demo' } catch {}
    $current=Inspect @('inspect',$id); $currentVolume=Inspect @('volume','inspect',$volumeName)
    [void](CheckOwned $current $currentVolume)
    if($current.State.Running){& docker stop $id *> $null}
    throw 'Separate review startup failed; its volume was preserved. Review private session logs.'
}
