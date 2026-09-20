param(
    [Parameter(Mandatory=$true)][string]$JavaExe,
    [Parameter(Mandatory=$true)][string]$ServerJar,
    [Parameter(Mandatory=$true)][string]$AgentJar,
    [Parameter(Mandatory=$true)][string]$ProjectDir
)

$ErrorActionPreference = 'Stop'

function Import-DotEnvFile {
    param([Parameter(Mandatory=$true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return 0 }

    $count = 0
    foreach ($rawLine in [System.IO.File]::ReadAllLines($Path, [System.Text.UTF8Encoding]::new($false))) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) { continue }
        if ($line.StartsWith('export ')) { $line = $line.Substring(7).Trim() }

        $eq = $line.IndexOf('=')
        if ($eq -le 0) { continue }

        $name = $line.Substring(0, $eq).Trim()
        $value = $line.Substring($eq + 1).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { continue }

        if ($value.Length -ge 2) {
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        $count++
    }
    return $count
}

$envFiles = @(
    (Join-Path $ProjectDir '.env'),
    (Join-Path $ProjectDir '.env.local')
)

$loadedFiles = @()
$total = 0
foreach ($file in $envFiles) {
    if (Test-Path -LiteralPath $file -PathType Leaf) {
        $n = Import-DotEnvFile -Path $file
        $total += $n
        $loadedFiles += [System.IO.Path]::GetFileName($file)
    }
}

if ($loadedFiles.Count -gt 0) {
    Write-Host ('TaxData environment yuklendi: ' + ($loadedFiles -join ' + ') + ' (' + $total + ' deyisen)') -ForegroundColor Green
} else {
    Write-Host 'DIQQET: .env / .env.local tapilmadi. Server cari Windows environment ile baslayacaq.' -ForegroundColor Yellow
    Write-Host 'Lokal production melumatlari lazimdirsa .env.server.example faylini .env kimi kopyalayib real deyerleri yazin.' -ForegroundColor Yellow
}

# Secret deyerleri ekrana cixarmadan yalnız vacib konfiqurasiyanin movcudlugunu gosteririk.
$profile = [Environment]::GetEnvironmentVariable('SPRING_PROFILES_ACTIVE', 'Process')
if ([string]::IsNullOrWhiteSpace($profile)) { $profile = '(default: dev)' }
Write-Host ('Spring profile: ' + $profile) -ForegroundColor Cyan

if ($profile -match '(^|,)\s*prod\s*(,|$)') {
    $required = @('SUPABASE_DB_URL','SUPABASE_DB_USER','SUPABASE_DB_PASSWORD','APP_ADMIN_PASSWORD')
    $missing = @()
    foreach ($key in $required) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) { $missing += $key }
    }
    if ($missing.Count -gt 0) {
        Write-Host ('XETA: prod profili secilib, amma bu environment deyisenleri yoxdur: ' + ($missing -join ', ')) -ForegroundColor Red
        Write-Host 'Real secretler ZIP-e daxil edilmir. Cari TaxData qovlugunda .env yaradib deyerleri yazin veya Railway Variables istifade edin.' -ForegroundColor Yellow
        exit 12
    }
}

if (-not (Test-Path -LiteralPath $JavaExe -PathType Leaf)) { throw "Java tapilmadi: $JavaExe" }
if (-not (Test-Path -LiteralPath $ServerJar -PathType Leaf)) { throw "Server JAR tapilmadi: $ServerJar" }
if (-not (Test-Path -LiteralPath $AgentJar -PathType Leaf)) { throw "Agent JAR tapilmadi: $AgentJar" }

& $JavaExe "-Dtaxdata.agent.jar=$AgentJar" -jar $ServerJar
exit $LASTEXITCODE
