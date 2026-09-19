param(
    [string]$DatabaseUrl = $env:DB_URL,
    [string]$DatabaseUsername = $env:DB_USERNAME,
    [string]$DatabasePassword = $env:DB_PASSWORD
)

$ErrorActionPreference = 'Continue'
$failed = $false
function Report([string]$Name, [bool]$Ok, [string]$Detail) {
    if (-not $Ok) { $script:failed = $true }
    $prefix = if ($Ok) { '[READY] ' } else { '[MISSING] ' }
    Write-Host ($prefix + $Name + ': ' + $Detail)
}

if ([string]::IsNullOrWhiteSpace($DatabaseUrl)) {
    Report 'PostgreSQL' $false 'DB_URL is not configured.'
} else {
    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if (-not $psql) {
        Report 'PostgreSQL' $false 'psql is not available on PATH.'
    } else {
        $oldPassword = $env:PGPASSWORD
        try {
            if ($DatabasePassword) { $env:PGPASSWORD = $DatabasePassword }
            $url = $DatabaseUrl -replace '^jdbc:', ''
            $args = @('--no-psqlrc', '--tuples-only', '--quiet', '--dbname', $url, '--command', 'SELECT 1')
            if ($DatabaseUsername) { $args += @('--username', $DatabaseUsername) }
            & $psql.Source @args *> $null
            Report 'PostgreSQL' ($LASTEXITCODE -eq 0) 'connection check'
        } finally { $env:PGPASSWORD = $oldPassword }
    }
}

foreach ($check in @(
    @{ Name='KURE'; Url='http://127.0.0.1:8091/v1/health' },
    @{ Name='Backend'; Url='http://127.0.0.1:8080/health' },
    @{ Name='Frontend'; Url='http://127.0.0.1:3000/' }
)) {
    try {
        $response = Invoke-WebRequest -Uri $check.Url -UseBasicParsing -TimeoutSec 4
        Report $check.Name ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) $check.Url
    } catch { Report $check.Name $false $check.Url }
}

if ($failed) { exit 1 }
