param(
  [string]$StateFile = ''
)

$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Write-State([string]$jdkHome) {
  if ([string]::IsNullOrWhiteSpace($StateFile)) { return }
  $dir = Split-Path -Parent $StateFile
  if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  # ASCII istifade etmeyin: Windows istifadeci adinda kiril/Unicode herfleri ola biler.
  [System.IO.File]::WriteAllText($StateFile, $jdkHome, (New-Object System.Text.UTF8Encoding($false)))
}

function Get-JdkReleaseVersion([string]$jdkHome) {
  if ([string]::IsNullOrWhiteSpace($jdkHome)) { return $null }
  $releaseFile = Join-Path $jdkHome 'release'
  if (-not (Test-Path -LiteralPath $releaseFile)) { return $null }
  try {
    $text = Get-Content -LiteralPath $releaseFile -Raw -ErrorAction Stop
    $m = [regex]::Match($text, '(?m)^JAVA_VERSION\s*=\s*"?([^"\r\n]+)"?')
    if ($m.Success) { return $m.Groups[1].Value.Trim() }
  } catch {}
  return $null
}

function Invoke-NativeCapture([string]$exe, [string]$arguments) {
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $exe
  $psi.Arguments = $arguments
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $p = New-Object System.Diagnostics.Process
  $p.StartInfo = $psi
  [void]$p.Start()
  $stdout = $p.StandardOutput.ReadToEnd()
  $stderr = $p.StandardError.ReadToEnd()
  $p.WaitForExit()
  return [pscustomobject]@{
    ExitCode = $p.ExitCode
    Text = (($stdout + "`n" + $stderr).Trim())
  }
}

function Get-Jdk21Probe([string]$jdkHome) {
  $result = [ordered]@{
    Home = $jdkHome
    ReleaseVersion = $null
    JavaExists = $false
    JavacExists = $false
    JavaExit = $null
    JavacExit = $null
    JavaText = ''
    JavacText = ''
    Valid = $false
  }
  if ([string]::IsNullOrWhiteSpace($jdkHome)) { return [pscustomobject]$result }

  $java = Join-Path $jdkHome 'bin\java.exe'
  $javac = Join-Path $jdkHome 'bin\javac.exe'
  $result.JavaExists = Test-Path -LiteralPath $java
  $result.JavacExists = Test-Path -LiteralPath $javac
  $result.ReleaseVersion = Get-JdkReleaseVersion $jdkHome

  if (-not $result.JavaExists -or -not $result.JavacExists) { return [pscustomobject]$result }
  if ([string]::IsNullOrWhiteSpace($result.ReleaseVersion) -or $result.ReleaseVersion -notmatch '^21(?:[\.\-\+_]|$)') {
    return [pscustomobject]$result
  }

  try {
    $j = Invoke-NativeCapture $java '-version'
    $c = Invoke-NativeCapture $javac '-version'
    $result.JavaExit = $j.ExitCode
    $result.JavacExit = $c.ExitCode
    $result.JavaText = $j.Text
    $result.JavacText = $c.Text
    $result.Valid = ($j.ExitCode -eq 0 -and $c.ExitCode -eq 0)
  } catch {
    $result.JavaText = $_.Exception.Message
  }
  return [pscustomobject]$result
}

function Test-Jdk21([string]$jdkHome) {
  $probe = Get-Jdk21Probe $jdkHome
  return [bool]$probe.Valid
}

function Show-Probe([object]$probe) {
  if (-not $probe) { return }
  Write-Host ('JDK namized yolu: ' + [string]$probe.Home) -ForegroundColor Yellow
  $releaseDisplay = if ($probe.ReleaseVersion) { [string]$probe.ReleaseVersion } else { 'tapilmadi' }
  Write-Host ('release JAVA_VERSION: ' + $releaseDisplay) -ForegroundColor Yellow
  Write-Host ('java.exe: ' + $probe.JavaExists + ' | exit=' + [string]$probe.JavaExit) -ForegroundColor Yellow
  if ($probe.JavaText) { Write-Host ('java: ' + (($probe.JavaText -replace "`r|`n", ' ') -replace '\s+',' ').Trim()) -ForegroundColor DarkYellow }
  Write-Host ('javac.exe: ' + $probe.JavacExists + ' | exit=' + [string]$probe.JavacExit) -ForegroundColor Yellow
  if ($probe.JavacText) { Write-Host ('javac: ' + (($probe.JavacText -replace "`r|`n", ' ') -replace '\s+',' ').Trim()) -ForegroundColor DarkYellow }
}

function Find-SystemJdk21 {
  if ($env:JAVA_HOME -and (Test-Jdk21 $env:JAVA_HOME)) { return $env:JAVA_HOME }
  try {
    $javacCmd = Get-Command javac.exe -ErrorAction Stop
    $jdkHome = Split-Path -Parent (Split-Path -Parent $javacCmd.Source)
    if (Test-Jdk21 $jdkHome) { return $jdkHome }
  } catch {}
  return $null
}

function Find-PrivateJdk21([string]$root) {
  if (-not (Test-Path -LiteralPath $root)) { return $null }

  # JDK arxivinde standart 'release' fayli kok qovluqdadir. Bu usul
  # javac output-un PowerShell terefinden ferqli formatlanmasindan asililiqi aradan qaldirir.
  $releaseFiles = @(Get-ChildItem -LiteralPath $root -Filter release -File -Recurse -ErrorAction SilentlyContinue)
  foreach ($release in $releaseFiles) {
    $jdkHome = $release.Directory.FullName
    if (Test-Jdk21 $jdkHome) { return $jdkHome }
  }

  # Ehtiyat fallback: qeyri-standart paketlerde javac.exe-den kok qovlugu tap.
  $javacs = @(Get-ChildItem -LiteralPath $root -Filter javac.exe -File -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\bin\\javac\.exe$' })
  foreach ($javac in $javacs) {
    $jdkHome = Split-Path -Parent (Split-Path -Parent $javac.FullName)
    if (Test-Jdk21 $jdkHome) { return $jdkHome }
  }
  return $null
}

function Find-FirstJdkCandidate([string]$root) {
  if (-not (Test-Path -LiteralPath $root)) { return $null }
  $release = Get-ChildItem -LiteralPath $root -Filter release -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($release) { return $release.Directory.FullName }
  $javac = Get-ChildItem -LiteralPath $root -Filter javac.exe -File -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\bin\\javac\.exe$' } | Select-Object -First 1
  if ($javac) { return Split-Path -Parent (Split-Path -Parent $javac.FullName) }
  return $null
}

$system = Find-SystemJdk21
if ($system) {
  Write-Host ('Java 21 JDK tapildi: ' + $system) -ForegroundColor Green
  Write-State $system
  exit 0
}

$base = Join-Path $env:LOCALAPPDATA 'TaxData\BuildJdk21'
$private = Find-PrivateJdk21 $base
if ($private) {
  Write-Host ('TaxData portable JDK 21 hazirdir: ' + $private) -ForegroundColor Green
  Write-State $private
  exit 0
}

Write-Host 'Java 21 JDK tapilmadi. TaxData portable JDK 21 avtomatik hazirlayir...' -ForegroundColor Yellow
$parent = Split-Path -Parent $base
if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }

# Evvelki ugursuz cehdlerden qalan staging qovluqlarini temizle.
Get-ChildItem -LiteralPath $parent -Directory -Filter 'BuildJdk21.installing.*' -ErrorAction SilentlyContinue |
  ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }

$stage = Join-Path $parent ('BuildJdk21.installing.' + [Guid]::NewGuid().ToString('N'))
$zip = Join-Path $stage 'jdk21.zip'
New-Item -ItemType Directory -Force -Path $stage | Out-Null

$arch = 'x64'
try {
  $osArch = [string](Get-CimInstance Win32_OperatingSystem -ErrorAction Stop).OSArchitecture
  if ($osArch -match '(?i)ARM') { $arch = 'aarch64' }
} catch {
  if ($env:PROCESSOR_ARCHITECTURE -match '(?i)ARM64' -or $env:PROCESSOR_ARCHITEW6432 -match '(?i)ARM64') { $arch = 'aarch64' }
}
$url = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$arch/jdk/hotspot/normal/eclipse"

try {
  $downloaded = $false
  for ($i=1; $i -le 4; $i++) {
    Write-Host ("Portable JDK 21 yuklenir... cehd $i/4")
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    try {
      Invoke-WebRequest -UseBasicParsing -TimeoutSec 300 -Uri $url -OutFile $zip
      if ((Test-Path -LiteralPath $zip) -and ((Get-Item -LiteralPath $zip).Length -gt 50000000)) { $downloaded=$true; break }
      throw 'Yuklenen JDK arxivi natamamdir.'
    } catch {
      Write-Host ('JDK endirme xetasi: ' + $_.Exception.Message) -ForegroundColor Yellow
      if ($i -lt 4) { Start-Sleep -Seconds ([Math]::Min(2*$i,6)) }
    }
  }
  if (-not $downloaded) { throw 'Portable JDK 21 4 cehdden sonra endirilmedi.' }

  Write-Host 'Portable JDK 21 acilir...'
  Expand-Archive -LiteralPath $zip -DestinationPath $stage -Force
  Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue

  $candidate = Find-PrivateJdk21 $stage
  if (-not $candidate) {
    $first = Find-FirstJdkCandidate $stage
    if ($first) { Show-Probe (Get-Jdk21Probe $first) }
    throw 'Endirilen JDK Java 21 / javac 21 yoxlamasindan kecmedi.'
  }

  # Yalniz tam yoxlamadan sonra permanent qovluga commit et. JDK-ni flat
  # qururuq ki yol her Windows istifadecisi ucun sabit olsun:
  # %LOCALAPPDATA%\TaxData\BuildJdk21\bin\java.exe
  if (Test-Path -LiteralPath $base) { Remove-Item -LiteralPath $base -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $base | Out-Null
  Get-ChildItem -LiteralPath $candidate -Force | ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination $base -Force }

  $private = Find-PrivateJdk21 $base
  if (-not $private) {
    $first = Find-FirstJdkCandidate $base
    if ($first) { Show-Probe (Get-Jdk21Probe $first) }
    throw 'Portable JDK commit-den sonra tapilmadi.'
  }

  Write-Host ('TaxData portable JDK 21 hazirdir: ' + $private) -ForegroundColor Green
  Write-State $private
  exit 0
} catch {
  Write-Host ('XETA: ' + $_.Exception.Message) -ForegroundColor Red
  exit 21
} finally {
  if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue }
}
