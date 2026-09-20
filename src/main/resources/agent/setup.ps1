$ErrorActionPreference='Stop'
$ExpectedVersion='__AGENT_VERSION__'
$ExpectedJarSha='__JAR_SHA256__'
$ExpectedJarSize=[long]'__JAR_SIZE__'
$Base=('__TAXDATA_BASE__').TrimEnd('/')
$Origin=('__TAXDATA_ORIGIN__').TrimEnd('/')
$root=Join-Path $env:LOCALAPPDATA 'TaxData'
$dir=Join-Path $root 'Agent'
$recoveryDir=Join-Path $root 'Recovery'
$stage=Join-Path $root ('.installing-'+[Guid]::NewGuid().ToString('N'))
$finalJar=Join-Path $dir 'taxdata-agent.jar'
$finalJre=Join-Path $dir 'jre'
$start=Join-Path $dir 'start-agent.ps1'
$watchdog=Join-Path $root 'watch-agent.ps1'
$repairCmd=Join-Path $recoveryDir 'TaxData-Agent-Repair.cmd'
$marker=Join-Path $dir '.install-complete'
$log=Join-Path $root 'install.log'
$installGuard=Join-Path $root '.agent-install.lock'
$runKey='HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$cacheDir=Join-Path $root 'Cache'
function Find-PowerShellExecutable{
  $candidates=New-Object System.Collections.Generic.List[string]
  try{$self=(Get-Process -Id $PID -ErrorAction Stop).Path;if($self){$candidates.Add($self)}}catch{}
  if($PSHOME){$candidates.Add((Join-Path $PSHOME 'powershell.exe'));$candidates.Add((Join-Path $PSHOME 'pwsh.exe'))}
  if($env:SystemRoot){
    $candidates.Add((Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'))
    $candidates.Add((Join-Path $env:SystemRoot 'Sysnative\WindowsPowerShell\v1.0\powershell.exe'))
    $candidates.Add((Join-Path $env:SystemRoot 'SysWOW64\WindowsPowerShell\v1.0\powershell.exe'))
  }
  if($env:ProgramFiles){$candidates.Add((Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe'))}
  foreach($name in @('powershell.exe','pwsh.exe')){try{$c=Get-Command $name -ErrorAction SilentlyContinue;if($c -and $c.Source){$candidates.Add($c.Source)}}catch{}}
  foreach($candidate in ($candidates|Where-Object{$_}|Select-Object -Unique)){try{if(Test-Path -LiteralPath $candidate){return (Resolve-Path -LiteralPath $candidate).Path}}catch{}}
  return $null
}
$PowerShellExe=Find-PowerShellExecutable
if([string]::IsNullOrWhiteSpace($PowerShellExe)){throw 'Windows PowerShell/PowerShell executable tapılmadı.'}
try{$psDir=Split-Path -Parent $PowerShellExe;if($psDir -and (($env:PATH -split ';') -notcontains $psDir)){$env:PATH=$psDir+';'+$env:PATH}}catch{}
$mutex=New-Object System.Threading.Mutex($false,'Local\TaxDataAgentInstaller')
if(-not $mutex.WaitOne(0)){ Write-Host 'TaxData Agent quraşdırılması artıq işləyir.' -ForegroundColor Yellow; exit 0 }

$backups=@{}
$oldRun=@{}
$commitStarted=$false

New-Item -ItemType Directory -Force -Path $root,$dir,$recoveryDir,$stage,$cacheDir | Out-Null
# Köhnə yarımçıq quraşdırma cəhdlərini təmizlə. Cari stage toxunulmaz qalır.
try{
  Get-ChildItem -LiteralPath $root -Directory -Filter '.installing-*' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -ne $stage } |
    ForEach-Object { try{Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction Stop}catch{} }
}catch{}
function Log([string]$m){try{Add-Content -Encoding UTF8 -Path $log -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss')+'  '+$m)}catch{}}
function Remove-Safe([string]$p){if($p -and (Test-Path $p)){try{Remove-Item -Recurse -Force $p -ErrorAction Stop}catch{}}}
function Find-CurlExecutable{
  $candidates=@()
  if($env:SystemRoot){$candidates+=(Join-Path $env:SystemRoot 'System32\curl.exe')}
  try{$c=Get-Command curl.exe -ErrorAction SilentlyContinue;if($c -and $c.Source){$candidates+=$c.Source}}catch{}
  foreach($candidate in ($candidates|Where-Object{$_}|Select-Object -Unique)){if(Test-Path -LiteralPath $candidate){return $candidate}}
  return $null
}
$CurlExe=Find-CurlExecutable
function Download-Retry([string]$uri,[string]$out,[long]$min,[string]$label){
  $last='';$maxAttempts=3;$timeout=if($min -ge 5000000){300}else{90}
  for($i=1;$i -le $maxAttempts;$i++){
    try{
      Remove-Safe $out
      Write-Host ($label+' yüklənir... '+$i+'/'+$maxAttempts)
      if($CurlExe){
        & $CurlExe '--fail' '--location' '--silent' '--show-error' '--retry' '2' '--retry-delay' '1' '--connect-timeout' '10' '--max-time' ([string]$timeout) '--output' $out $uri
        if($LASTEXITCODE -ne 0){throw ($label+' curl xətası: '+$LASTEXITCODE)}
      }else{
        Invoke-WebRequest -UseBasicParsing -TimeoutSec $timeout -Uri $uri -OutFile $out
      }
      if(-not (Test-Path $out)){throw ($label+' faylı yaranmadı.')}
      if((Get-Item $out).Length -lt $min){throw ($label+' natamam yükləndi.')}
      return
    }catch{$last=$_.Exception.Message;Remove-Safe $out;Log ($label+' yükləmə cəhdi '+$i+' alınmadı: '+$last);if($i -lt $maxAttempts){Start-Sleep -Seconds $i}}
  }
  throw ($label+' yüklənmədi. Son xəta: '+$last)
}
function Sha256([string]$p){return (Get-FileHash -Algorithm SHA256 -Path $p).Hash.ToLowerInvariant()}
function Assert-AgentJar([string]$p){
  if(-not (Test-Path $p)){throw 'Agent JAR tapılmadı.'}
  $len=(Get-Item $p).Length
  if($len -ne $ExpectedJarSize){throw ('Agent JAR ölçüsü uyğun deyil. Gözlənilən '+$ExpectedJarSize+', alınan '+$len+'.')}
  if((Sha256 $p) -ne $ExpectedJarSha){throw 'Agent JAR SHA-256 yoxlamasından keçmədi.'}
}
function Invoke-NativeCapture([string]$exe,[string]$arguments){
  $psi=New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName=$exe;$psi.Arguments=$arguments;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true
  $psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
  $p=New-Object System.Diagnostics.Process;$p.StartInfo=$psi
  [void]$p.Start();$stdout=$p.StandardOutput.ReadToEnd();$stderr=$p.StandardError.ReadToEnd();$p.WaitForExit()
  return [pscustomobject]@{ExitCode=$p.ExitCode;Text=(($stdout+"`n"+$stderr).Trim())}
}
function Get-JavaReleaseVersion([string]$javaHome){
  if([string]::IsNullOrWhiteSpace($javaHome)){return $null}
  $release=Join-Path $javaHome 'release';if(-not(Test-Path -LiteralPath $release)){return $null}
  try{$text=Get-Content -LiteralPath $release -Raw -ErrorAction Stop;$m=[regex]::Match($text,'(?m)^JAVA_VERSION\s*=\s*"?([^"\r\n]+)"?');if($m.Success){return $m.Groups[1].Value.Trim()}}catch{}
  return $null
}
function Get-Java21Probe([string]$javaHome){
  $r=[ordered]@{Home=$javaHome;ReleaseVersion=$null;JavaExists=$false;JavaExit=$null;JavaText='';Valid=$false;Launch='';Check=''}
  if([string]::IsNullOrWhiteSpace($javaHome)){return [pscustomobject]$r}
  $java=Join-Path $javaHome 'bin\java.exe';$javaw=Join-Path $javaHome 'bin\javaw.exe'
  $r.JavaExists=Test-Path -LiteralPath $java;$r.ReleaseVersion=Get-JavaReleaseVersion $javaHome
  if(-not $r.JavaExists){return [pscustomobject]$r}
  if([string]::IsNullOrWhiteSpace($r.ReleaseVersion)-or $r.ReleaseVersion -notmatch '^21(?:[\.\-\+_]|$)'){return [pscustomobject]$r}
  try{$x=Invoke-NativeCapture $java '-version';$r.JavaExit=$x.ExitCode;$r.JavaText=$x.Text;$r.Valid=($x.ExitCode -eq 0 -and $x.Text -match '(?i)(?:version\s+"?21(?:[\.\-\+_]|$)|openjdk\s+21(?:[\.\-\+_]|$))');if($r.Valid){$r.Check=$java;$r.Launch=$java}}catch{$r.JavaText=$_.Exception.Message}
  return [pscustomobject]$r
}
function Show-JavaProbe([object]$probe){
  if(-not $probe){return}
  Write-Host ('Java namizəd yolu: '+[string]$probe.Home) -ForegroundColor Yellow
  Write-Host ('release JAVA_VERSION: '+$(if($probe.ReleaseVersion){[string]$probe.ReleaseVersion}else{'tapılmadı'})) -ForegroundColor Yellow
  Write-Host ('java.exe: '+$probe.JavaExists+' | exit='+[string]$probe.JavaExit) -ForegroundColor Yellow
  if($probe.JavaText){Write-Host ('java: '+(($probe.JavaText -replace "`r|`n",' ') -replace '\s+',' ').Trim()) -ForegroundColor DarkYellow}
}
function Find-SystemJava21{
  $homes=New-Object System.Collections.Generic.List[string]
  if($env:JAVA_HOME){$homes.Add($env:JAVA_HOME)}
  foreach($n in @('java.exe','javaw.exe')){try{$c=Get-Command $n -ErrorAction SilentlyContinue;if($c -and $c.Source){$bin=[IO.Path]::GetDirectoryName($c.Source);if($bin){$homes.Add([IO.Path]::GetDirectoryName($bin))}}}catch{}}
  foreach($javaHomeCandidate in ($homes|Select-Object -Unique)){try{$probe=Get-Java21Probe $javaHomeCandidate;if($probe.Valid){return [pscustomobject]@{Launch=$probe.Launch;Check=$probe.Check;Home=$probe.Home}}}catch{}}
  return $null
}
function Find-PrivateJava21([string]$baseDir){
  if(-not(Test-Path -LiteralPath $baseDir)){return $null}
  # Fast path: Temurin arxivində JAVA_HOME adətən kökdə və ya ilk alt qovluqdadır.
  foreach($jdkHomeCandidate in @($baseDir)+@(Get-ChildItem -LiteralPath $baseDir -Directory -ErrorAction SilentlyContinue|ForEach-Object{$_.FullName})){
    try{$probe=Get-Java21Probe $jdkHomeCandidate;if($probe.Valid){return [pscustomobject]@{Launch=$probe.Launch;Check=$probe.Check;Home=$probe.Home}}}catch{}
  }
  # Yalnız qeyri-standart paket quruluşunda dərin axtarış et.
  $release=Get-ChildItem -LiteralPath $baseDir -Recurse -Filter release -File -ErrorAction SilentlyContinue|Select-Object -First 1
  if($release){$probe=Get-Java21Probe $release.Directory.FullName;if($probe.Valid){return [pscustomobject]@{Launch=$probe.Launch;Check=$probe.Check;Home=$probe.Home}}}
  $java=Get-ChildItem -LiteralPath $baseDir -Recurse -Filter java.exe -File -ErrorAction SilentlyContinue|Where-Object{$_.FullName -match '\\bin\\java\.exe$'}|Select-Object -First 1
  if($java){$javaHomeCandidate=Split-Path -Parent (Split-Path -Parent $java.FullName);$probe=Get-Java21Probe $javaHomeCandidate;if($probe.Valid){return [pscustomobject]@{Launch=$probe.Launch;Check=$probe.Check;Home=$probe.Home}}}
  return $null
}
function Find-FirstJavaCandidate([string]$baseDir){
  if(-not(Test-Path -LiteralPath $baseDir)){return $null}
  $release=Get-ChildItem -LiteralPath $baseDir -Recurse -Filter release -File -ErrorAction SilentlyContinue|Select-Object -First 1
  if($release){return $release.Directory.FullName}
  $java=Get-ChildItem -LiteralPath $baseDir -Recurse -Filter java.exe -File -ErrorAction SilentlyContinue|Where-Object{$_.FullName -match '\\bin\\java\.exe$'}|Select-Object -First 1
  if($java){return Split-Path -Parent (Split-Path -Parent $java.FullName)}
  return $null
}
function Get-AgentPortOwnerPids{
  $ids=@()
  try{
    $ids+=@(Get-NetTCPConnection -LocalPort 47631 -State Listen -ErrorAction SilentlyContinue|ForEach-Object{$_.OwningProcess})
  }catch{}
  if($ids.Count -eq 0){
    try{
      foreach($line in @(netstat -ano -p tcp 2>$null)){
        if($line -match '(?i)\s(?:127\.0\.0\.1|0\.0\.0\.0|\[::1\]|\[::\]):47631\s+\S+\s+LISTENING\s+(\d+)\s*$'){$ids+=[int]$Matches[1]}
      }
    }catch{}
  }
  return @($ids|Where-Object{$_ -and $_ -gt 0}|Select-Object -Unique)
}
function Get-LocalAgentStatus{
  try{return Invoke-RestMethod -UseBasicParsing -TimeoutSec 2 -Headers @{'X-TAXDATA-Agent'='1'} -Uri 'http://127.0.0.1:47631/api/status'}catch{return $null}
}
# Legacy pre-TaxData installation cleanup. The old product name is intentionally
# assembled from character codes so it never appears in the current product UI,
# filenames, docs or source text as a visible brand string.
$legacyName=([string][char]69)+([char]84)+([char]84)+([char]82)+([char]79)+([char]69)
$legacyRoot=Join-Path $env:LOCALAPPDATA $legacyName
function Is-LegacyAgentProcess([object]$proc){
  if($null -eq $proc){return $false}
  try{
    $cmd=[string]$proc.CommandLine
    if([string]::IsNullOrWhiteSpace($cmd)){return $false}
    return ($cmd.IndexOf($legacyRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0)
  }catch{return $false}
}
function Stop-LegacyAgentComponents{
  try{
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue|Where-Object{
      $_.ProcessId -ne $PID -and (Is-LegacyAgentProcess $_)
    }|ForEach-Object{
      try{
        Write-Host ('Köhnə lokal agent prosesi dayandırılır. PID '+$_.ProcessId) -ForegroundColor Yellow
        Log ('Köhnə lokal agent prosesi dayandırılır. PID '+$_.ProcessId)
        Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop
      }catch{}
    }
  }catch{}
}
function Remove-LegacyAutostart{
  try{
    $r=Get-ItemProperty -Path $runKey -ErrorAction SilentlyContinue
    if($r){
      foreach($prop in $r.PSObject.Properties){
        if($prop.Name -like 'PS*'){continue}
        $value=[string]$prop.Value
        if($value -and $value.IndexOf($legacyRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0){
          try{Remove-ItemProperty -Path $runKey -Name $prop.Name -ErrorAction Stop;Log ('Köhnə autostart qeydi silindi: '+$prop.Name)}catch{}
        }
      }
    }
  }catch{}
}
function Remove-LegacyAgentFiles{
  # Run only after the new TaxData Agent has passed its final health checks.
  Remove-LegacyAutostart
  if(-not(Test-Path -LiteralPath $legacyRoot)){return}
  try{
    Stop-LegacyAgentComponents
    Start-Sleep -Milliseconds 500
    Remove-Item -LiteralPath $legacyRoot -Recurse -Force -ErrorAction Stop
    Log 'Köhnə lokal agent qovluğu uğurla təmizləndi.'
    Write-Host 'Köhnə lokal agent faylları təmizləndi.' -ForegroundColor DarkGray
  }catch{Log ('Köhnə lokal agent qovluğu tam silinmədi: '+$_.Exception.Message)}
}
function Stop-OldWatchdogs{
  try{
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue|Where-Object{
      $_.ProcessId -ne $PID -and $_.CommandLine -and $_.CommandLine -like '*watch-agent.ps1*' -and $_.CommandLine -like '*TaxData*'
    }|ForEach-Object{try{Write-Host ('Köhnə TaxData watchdog dayandırılır. PID '+$_.ProcessId) -ForegroundColor Yellow;Log ('Köhnə watchdog dayandırılır. PID '+$_.ProcessId);Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop}catch{}}
  }catch{}
}
function Stop-KnownAgentProcesses{
  try{
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue|Where-Object{
      $_.ProcessId -ne $PID -and $_.CommandLine -and $_.CommandLine -like '*taxdata-agent.jar*'
    }|ForEach-Object{try{Write-Host ('Köhnə TaxData Agent prosesi dayandırılır. PID '+$_.ProcessId) -ForegroundColor Yellow;Log ('Köhnə TaxData Agent prosesi dayandırılır. PID '+$_.ProcessId);Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop}catch{}}
  }catch{}
}
function Describe-Process([int]$id){
  try{
    $p=Get-CimInstance Win32_Process -Filter ('ProcessId='+$id) -ErrorAction Stop
    $cmd=([string]$p.CommandLine).Trim();if($cmd.Length -gt 220){$cmd=$cmd.Substring(0,220)+'...'}
    $desc=([string]$p.Name)+' | PID '+$id
    if($cmd){$desc+=' | '+$cmd}
    return $desc
  }catch{return ('PID '+$id)}
}
function Ensure-AgentPortFree{
  # Əksər quraşdırmalarda port artıq boş olur. Bahalı CIM proses skanlarını yalnız lazım olduqda et.
  $initialOwners=@(Get-AgentPortOwnerPids)
  if($initialOwners.Count -eq 0){return}
  # Mövcud watchdog/legacy agent biz portu boşaldarkən prosesi yenidən qaldırmasın.
  Stop-LegacyAgentComponents
  Stop-OldWatchdogs
  Stop-KnownAgentProcesses
  $deadline=(Get-Date).AddSeconds(8)
  do{
    $owners=@(Get-AgentPortOwnerPids)
    if($owners.Count -eq 0){return}
    $status=Get-LocalAgentStatus
    if($status -and $status.ok -eq $true){
      # 47631-də cavab verən servis özünü TaxData Agent kimi təsdiqlədi.
      foreach($id in $owners){try{Write-Host ('47631 portundakı köhnə TaxData Agent dayandırılır. '+(Describe-Process $id)) -ForegroundColor Yellow;Log ('47631 portundakı köhnə TaxData Agent dayandırılır. '+(Describe-Process $id));Stop-Process -Id $id -Force -ErrorAction Stop}catch{}}
    }else{
      $killed=$false
      foreach($id in $owners){
        try{
          $p=Get-CimInstance Win32_Process -Filter ('ProcessId='+$id) -ErrorAction Stop
          $cmd=[string]$p.CommandLine
          if($cmd -and $cmd -like '*taxdata-agent.jar*'){
            Log ('47631 portunda TaxData Agent command line tapıldı və dayandırılır. '+(Describe-Process $id));Stop-Process -Id $id -Force -ErrorAction Stop;$killed=$true
          }elseif(Is-LegacyAgentProcess $p){
            Write-Host ('47631 portundakı köhnə lokal agent avtomatik dayandırılır. '+(Describe-Process $id)) -ForegroundColor Yellow
            Log ('47631 portundakı köhnə lokal agent avtomatik dayandırılır. '+(Describe-Process $id))
            Stop-Process -Id $id -Force -ErrorAction Stop;$killed=$true
          }
        }catch{}
      }
      if(-not $killed){
        $desc=($owners|ForEach-Object{Describe-Process ([int]$_)}) -join '; '
        throw ('47631 portu başqa və ya tanınmayan proses tərəfindən istifadə olunur: '+$desc+'. Təhlükəsizlik üçün həmin proses avtomatik dayandırılmadı.')
      }
    }
    Start-Sleep -Milliseconds 500
  }while((Get-Date) -lt $deadline)
  $left=@(Get-AgentPortOwnerPids)
  if($left.Count -gt 0){throw ('47631 portu boşalmadı. PID: '+($left -join ', '))}
}
function Stop-Agent{
  Stop-LegacyAgentComponents
  Stop-OldWatchdogs
  Stop-KnownAgentProcesses
}
$agentHeaders=@{'X-TAXDATA-Agent'='1'}
function MarkerValueCurrent([string]$key){
  try{if(-not(Test-Path -LiteralPath $marker)){return ''};foreach($line in Get-Content -LiteralPath $marker -ErrorAction Stop){$clean=([string]$line).TrimStart([char]0xFEFF);if($clean.StartsWith($key+'=')){return $clean.Substring($key.Length+1).Trim()}}}catch{};return ''
}
function Test-FastReady{
  try{
    if(-not(Test-Path -LiteralPath $finalJar) -or -not(Test-Path -LiteralPath $start) -or -not(Test-Path -LiteralPath $watchdog) -or -not(Test-Path -LiteralPath $repairCmd) -or -not(Test-Path -LiteralPath $marker)){return $false}
    if((MarkerValueCurrent 'version') -ne $ExpectedVersion){return $false}
    if((MarkerValueCurrent 'jarSha256').ToLowerInvariant() -ne $ExpectedJarSha){return $false}
    if((Get-Item -LiteralPath $finalJar).Length -ne $ExpectedJarSize){return $false}
    if((Sha256 $finalJar) -ne $ExpectedJarSha){return $false}
    $javaPath=MarkerValueCurrent 'javaPath';if([string]::IsNullOrWhiteSpace($javaPath) -or -not(Test-Path -LiteralPath $javaPath)){return $false}
    $javaHome=Split-Path -Parent (Split-Path -Parent $javaPath);if(-not(Get-Java21Probe $javaHome).Valid){return $false}
    try{$repairText=Get-Content -LiteralPath $repairCmd -Raw -ErrorAction Stop;if((Get-Item -LiteralPath $repairCmd).Length -le 500 -or -not $repairText.Contains($ExpectedVersion) -or -not $repairText.Contains($Base)){return $false}}catch{return $false}
    try{$run=Get-ItemProperty -Path $runKey -ErrorAction Stop;if([string]$run.'TaxData Agent' -notlike ('*'+$finalJar+'*') -or [string]$run.'TaxData Agent Watchdog' -notlike ('*'+$watchdog+'*')){return $false}}catch{return $false}
    $s=Get-LocalAgentStatus
    return ($s -and $s.ok -eq $true -and $s.installComplete -eq $true -and [string]$s.version -eq $ExpectedVersion)
  }catch{return $false}
}
function Wait-Process{
  for($i=0;$i -lt 24;$i++){try{$s=Invoke-RestMethod -UseBasicParsing -TimeoutSec 1 -Headers $agentHeaders -Uri 'http://127.0.0.1:47631/api/status';if($s.ok -eq $true -and [string]$s.version -eq $ExpectedVersion){return $true}}catch{};Start-Sleep -Milliseconds 500};return $false
}
function Wait-Complete{
  for($i=0;$i -lt 20;$i++){try{$s=Invoke-RestMethod -UseBasicParsing -TimeoutSec 1 -Headers $agentHeaders -Uri 'http://127.0.0.1:47631/api/status';if($s.installComplete -eq $true -and [string]$s.version -eq $ExpectedVersion){return $true}}catch{};Start-Sleep -Milliseconds 400};return $false
}
function Start-AgentDirect([string]$javaExe,[string]$jarPath,[string]$allowedOrigin){
  $stdout=Join-Path $dir 'agent-stdout.log';$stderr=Join-Path $dir 'agent-stderr.log';$launchLog=Join-Path $dir 'agent-launch.log'
  Remove-Safe $stdout;Remove-Safe $stderr;Remove-Safe $launchLog
  if(-not(Test-Path -LiteralPath $javaExe)){throw ('Agent Java executable tapılmadı: '+$javaExe)}
  if(-not(Test-Path -LiteralPath $jarPath)){throw ('Agent JAR tapılmadı: '+$jarPath)}
  try{
    Add-Content -Encoding UTF8 -Path $launchLog -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss')+' direct-launch java='+$javaExe+' jar='+$jarPath)
    # Windows PowerShell 5.1 Start-Process array arguments can lose quoting for paths with spaces.
    # Build one explicit Windows command line and quote the JAR path ourselves.
    $safeJar=$jarPath.Replace('"','\"')
    $safeOrigin=$allowedOrigin.Replace('"','\"')
    $argumentLine='-Dtaxdata.agent.allowed-origin="'+$safeOrigin+'" -Dfile.encoding=UTF-8 -jar "'+$safeJar+'" --server.address=127.0.0.1 --server.port=47631'
    Add-Content -Encoding UTF8 -Path $launchLog -Value ('arguments='+$argumentLine)
    $proc=Start-Process -PassThru -WindowStyle Hidden -FilePath $javaExe -ArgumentList $argumentLine -RedirectStandardOutput $stdout -RedirectStandardError $stderr -ErrorAction Stop
    Add-Content -Encoding UTF8 -Path $launchLog -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss')+' PID='+$proc.Id)
    Start-Sleep -Milliseconds 450
    try{if($proc.HasExited){Add-Content -Encoding UTF8 -Path $launchLog -Value ('Java erkən dayandı. ExitCode='+$proc.ExitCode);Show-AgentStartDiagnostics;throw ('Agent Java prosesi erkən dayandı. ExitCode='+$proc.ExitCode)}}catch{if($_.Exception.Message -like 'Agent Java prosesi*'){throw}}
    return $proc
  }catch{
    try{Add-Content -Encoding UTF8 -Path $launchLog -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss')+' START XETA: '+$_.Exception.Message)}catch{}
    throw ('Agent Java prosesi birbaşa başladıla bilmədi: '+$_.Exception.Message)
  }
}
function Show-AgentStartDiagnostics{
  $err=Join-Path $dir 'agent-stderr.log';$out=Join-Path $dir 'agent-stdout.log'
  Write-Host 'Agent start diaqnostikasi:' -ForegroundColor Yellow
  $shown=$false
  foreach($p in @($err,$out)){
    if(Test-Path -LiteralPath $p){
      try{$lines=@(Get-Content -LiteralPath $p -Tail 12 -ErrorAction Stop);if($lines.Count -gt 0){$shown=$true;Write-Host ('--- '+[IO.Path]::GetFileName($p)+' ---') -ForegroundColor DarkYellow;foreach($line in $lines){Write-Host $line -ForegroundColor DarkYellow;Log ('agent-start: '+$line)}}}catch{}
    }
  }
  if(-not $shown){Write-Host 'Java prosesi log yaratmayib. Agent process/port yoxlamasi edilir...' -ForegroundColor DarkYellow}
  try{$p=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue|Where-Object{$_.CommandLine -like '*taxdata-agent.jar*'}|Select-Object -First 1;if($p){Write-Host ('Agent process PID: '+$p.ProcessId) -ForegroundColor DarkYellow}else{Write-Host 'Agent prosesi tapilmadi.' -ForegroundColor DarkYellow}}catch{}
  try{$owners=@(Get-AgentPortOwnerPids);if($owners.Count -gt 0){Write-Host ('47631 port owner PID: '+($owners -join ', ')) -ForegroundColor DarkYellow;foreach($id in $owners){Write-Host (Describe-Process ([int]$id)) -ForegroundColor DarkYellow}}else{Write-Host '47631 portu bosdur.' -ForegroundColor DarkYellow}}catch{}
}

function Backup([string]$p,[string]$key){if(Test-Path $p){$b=$p+'.previous';Remove-Safe $b;Move-Item -Force $p $b;$script:backups[$key]=@($p,$b)}}
function Restore-Backups{foreach($kv in $backups.GetEnumerator()){$p=$kv.Value[0];$b=$kv.Value[1];Remove-Safe $p;if(Test-Path $b){Move-Item -Force $b $p}}}
function Drop-Backups{foreach($kv in $backups.GetEnumerator()){Remove-Safe $kv.Value[1]}}
function Save-RunValues{try{$r=Get-ItemProperty -Path $runKey -ErrorAction SilentlyContinue;foreach($n in @('TaxData Agent','TaxData Agent Watchdog')){$oldRun[$n]=if($r){$r.$n}else{$null}}}catch{}}
function Restore-RunValues{New-Item -Path $runKey -Force|Out-Null;foreach($n in $oldRun.Keys){$v=$oldRun[$n];if($null -eq $v -or [string]::IsNullOrWhiteSpace([string]$v)){Remove-ItemProperty -Path $runKey -Name $n -ErrorAction SilentlyContinue}else{Set-ItemProperty -Path $runKey -Name $n -Value ([string]$v)}}}

# Sürətli yol: artıq tam sağlam və eyni build quraşdırılıbsa heç nə yenidən yükləmə.
if(Test-FastReady){
  Remove-Safe $stage
  Write-Host 'TaxData Agent artıq tam hazırdır. Yenidən yükləmə tələb olunmadı.' -ForegroundColor Green
  Log ('Sürətli yoxlama: Agent '+$ExpectedVersion+' artıq sağlamdır; quraşdırma ötürüldü.')
  exit 0
}

try{
  Set-Content -Encoding ASCII -Path $installGuard -Value ([DateTime]::UtcNow.ToString('o'))
  Log ('Quraşdırma/bərpa başladı. Versiya '+$ExpectedVersion)

  $stagedJar=Join-Path $stage 'taxdata-agent.jar'
  # Eyni və sağlam JAR artıq diskdədirsə şəbəkədən yenidən yükləmə; SHA/ölçü yenə yoxlanılır.
  $reusedJar=$false
  if(Test-Path -LiteralPath $finalJar){
    try{
      if((Get-Item -LiteralPath $finalJar).Length -eq $ExpectedJarSize -and (Sha256 $finalJar) -eq $ExpectedJarSha){
        Copy-Item -Force -LiteralPath $finalJar -Destination $stagedJar
        $reusedJar=$true
        Write-Host 'Mövcud Agent JAR sağlamdır, lokal nüsxə istifadə olunur.' -ForegroundColor DarkGray
      }
    }catch{}
  }
  if(-not $reusedJar){Download-Retry ($Base+'/agent/taxdata-agent.jar') $stagedJar 100000 'TaxData Agent'}
  Assert-AgentJar $stagedJar

  $systemJava=Find-SystemJava21
  $existingPrivate=Find-PrivateJava21 $finalJre
  $javaMode='system';$javaLaunch='';$javaCheck='';$replaceJre=$false
  if($systemJava){
    $javaLaunch=$systemJava.Launch;$javaCheck=$systemJava.Check
    Write-Host 'Sistem Java 21 tapıldı; runtime yüklənməyəcək.' -ForegroundColor DarkGray
  }elseif($existingPrivate){
    $javaMode='private';$javaLaunch=$existingPrivate.Launch;$javaCheck=$existingPrivate.Check
    Write-Host 'Mövcud private Java 21 bütövdür; runtime yüklənməyəcək.' -ForegroundColor DarkGray
  }else{
    $javaMode='private';$replaceJre=$true
    $stagedJre=Join-Path $stage 'jre'
    $runtimeArch='x64'
    if($env:PROCESSOR_ARCHITECTURE -match '(?i)ARM64' -or $env:PROCESSOR_ARCHITEW6432 -match '(?i)ARM64'){$runtimeArch='aarch64'}
    $runtimeUrl=('https://api.adoptium.net/v3/binary/latest/21/ga/windows/'+$runtimeArch+'/jre/hotspot/normal/eclipse')
    $jreZip=Join-Path $cacheDir ('temurin-jre21-'+$runtimeArch+'.zip')
    $cacheOk=$false
    if(Test-Path -LiteralPath $jreZip){
      try{if((Get-Item -LiteralPath $jreZip).Length -ge 5000000){$cacheOk=$true;Write-Host 'Java 21 runtime keşdən istifadə olunur.' -ForegroundColor DarkGray}}catch{}
    }
    if(-not $cacheOk){Download-Retry $runtimeUrl $jreZip 5000000 'Java 21 runtime'}
    New-Item -ItemType Directory -Force -Path $stagedJre|Out-Null
    $expanded=$false
    $tar=$null
    try{$tc=Get-Command tar.exe -ErrorAction SilentlyContinue;if($tc -and $tc.Source){$tar=$tc.Source}}catch{}
    if($tar){
      try{& $tar '-xf' $jreZip '-C' $stagedJre;if($LASTEXITCODE -eq 0){$expanded=$true}}catch{}
    }
    if(-not $expanded){Expand-Archive -Force $jreZip $stagedJre}
    $j=Find-PrivateJava21 $stagedJre
    if(-not $j -and $cacheOk){
      Write-Host 'Java keşi uyğun deyil, təmiz nüsxə yenidən yüklənir.' -ForegroundColor Yellow
      Remove-Safe $stagedJre;Remove-Safe $jreZip
      Download-Retry $runtimeUrl $jreZip 5000000 'Java 21 runtime'
      New-Item -ItemType Directory -Force -Path $stagedJre|Out-Null
      $expanded=$false
      if($tar){try{& $tar '-xf' $jreZip '-C' $stagedJre;if($LASTEXITCODE -eq 0){$expanded=$true}}catch{}}
      if(-not $expanded){Expand-Archive -Force $jreZip $stagedJre}
      $j=Find-PrivateJava21 $stagedJre
    }
    if(-not $j){
      $candidate=Find-FirstJavaCandidate $stagedJre
      if($candidate){Show-JavaProbe (Get-Java21Probe $candidate)}
      throw 'Yüklənən Java runtime Java 21 yoxlamasından keçmədi.'
    }
    $javaLaunch=$j.Launch;$javaCheck=$j.Check
  }

  $stagedRepair=Join-Path $stage 'TaxData-Agent-Repair.cmd'
  $repairReused=$false
  if(Test-Path -LiteralPath $repairCmd){
    try{
      $rt=Get-Content -LiteralPath $repairCmd -Raw -ErrorAction Stop
      if((Get-Item -LiteralPath $repairCmd).Length -gt 500 -and $rt.Contains($ExpectedVersion) -and $rt.Contains($Base)){
        Copy-Item -Force -LiteralPath $repairCmd -Destination $stagedRepair
        $repairReused=$true
        Write-Host 'Bərpa quraşdırıcısı lokal nüsxədən istifadə olunur.' -ForegroundColor DarkGray
      }
    }catch{}
  }
  if(-not $repairReused){Download-Retry ($Base+'/agent/installer') $stagedRepair 500 'TaxData bərpa quraşdırıcısı'}

  Save-RunValues
  Ensure-AgentPortFree
  $commitStarted=$true
  Backup $finalJar 'jar';Backup $start 'start';Backup $watchdog 'watchdog';Backup $repairCmd 'repair';Backup $marker 'marker'
  if($replaceJre){Backup $finalJre 'jre'}

  Move-Item -Force $stagedJar $finalJar
  if($replaceJre){Move-Item -Force (Join-Path $stage 'jre') $finalJre;$j=Find-PrivateJava21 $finalJre;if(-not $j){throw 'Private Java commit-dən sonra tapılmadı.'};$javaLaunch=$j.Launch;$javaCheck=$j.Check}

  $javaEsc=$javaLaunch.Replace("'","''");$jarEsc=$finalJar.Replace("'","''");$originEsc=$Origin.Replace("'","''")
  $launchScript=@"
`$ErrorActionPreference='Stop'
`$java='$javaEsc'
`$jar='$jarEsc'
`$agentDir=Split-Path -Parent `$jar
`$stdout=Join-Path `$agentDir 'agent-stdout.log'
`$stderr=Join-Path `$agentDir 'agent-stderr.log'
if(-not(Test-Path `$java)){exit 21}
if(-not(Test-Path `$jar)){exit 22}
Remove-Item -Force `$stdout,`$stderr -ErrorAction SilentlyContinue
`$safeJar=`$jar.Replace('"','\"')
`$safeOrigin='$originEsc'.Replace('"','\"')
`$argumentLine='-Dtaxdata.agent.allowed-origin="'+`$safeOrigin+'" -Dfile.encoding=UTF-8 -jar "'+`$safeJar+'" --server.address=127.0.0.1 --server.port=47631'
Start-Process -WindowStyle Hidden -FilePath `$java -ArgumentList `$argumentLine -RedirectStandardOutput `$stdout -RedirectStandardError `$stderr
"@
  Set-Content -Encoding UTF8 -Path $start -Value $launchScript
  try{Unblock-File -LiteralPath $start -ErrorAction SilentlyContinue}catch{}

  Move-Item -Force $stagedRepair $repairCmd
  # Internetdən endirilmiş fayllarda Zone.Identifier varsa cari istifadəçi səviyyəsində təmizlə.
  # Bu administrator hüququ tələb etmir və sadəcə TaxData/Temurin fayllarına tətbiq olunur.
  try{Unblock-File -LiteralPath $finalJar -ErrorAction SilentlyContinue}catch{}
  try{Unblock-File -LiteralPath $repairCmd -ErrorAction SilentlyContinue}catch{}
  if($replaceJre){
    # Minlərlə runtime faylını ayrıca Unblock-File etmək ləngdir; yalnız Java icra fayllarını aç.
    foreach($jp in @($javaLaunch,$javaCheck)){
      if($jp -and (Test-Path -LiteralPath $jp)){try{Unblock-File -LiteralPath $jp -ErrorAction SilentlyContinue}catch{}}
    }
  }

  $psEsc=$PowerShellExe.Replace("'","''")
  $watchdogScript=@"
`$ErrorActionPreference='SilentlyContinue'
`$powerShellExe='$psEsc'
`$ExpectedVersion='$ExpectedVersion'
`$root=Join-Path `$env:LOCALAPPDATA 'TaxData'
`$agentDir=Join-Path `$root 'Agent'
`$start=Join-Path `$agentDir 'start-agent.ps1'
`$repairDir=Join-Path `$root 'Recovery'
`$repairCmd=Join-Path `$repairDir 'TaxData-Agent-Repair.cmd'
`$repairUrl='$Base/agent/installer'
`$setupUrl='$Base/agent/bootstrap'
`$manifestUrl='$Base/agent/manifest'
`$marker=Join-Path `$agentDir '.install-complete'
`$log=Join-Path `$root 'repair.log'
`$guard=Join-Path `$root '.agent-install.lock'
`$outer=Join-Path `$root '.agent-installer.lock'
`$restartAfterUpdate=`$false
`$lastManifestCheck=[DateTime]::MinValue
`$mutex=New-Object System.Threading.Mutex(`$false,'Local\TaxDataAgentWatchdog')
if(-not `$mutex.WaitOne(0)){exit 0}
function RLog([string]`$m){try{Add-Content -Encoding UTF8 -Path `$log -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss')+'  '+`$m)}catch{}}
function LocalStatus{try{return Invoke-RestMethod -UseBasicParsing -TimeoutSec 2 -Headers @{'X-TAXDATA-Agent'='1'} -Uri 'http://127.0.0.1:47631/api/status'}catch{return `$null}}
function Ready{`$s=LocalStatus;return (`$null -ne `$s -and `$s.ok -eq `$true -and `$s.installComplete -eq `$true -and [string]`$s.version -eq `$ExpectedVersion)}
function AgentBusy{`$s=LocalStatus;return (`$null -ne `$s -and `$s.busy -eq `$true)}
function RemoteManifest{
  try{
    `$m=Invoke-RestMethod -UseBasicParsing -TimeoutSec 10 -Headers @{'Cache-Control'='no-cache'} -Uri `$manifestUrl
    if(`$m -and -not [string]::IsNullOrWhiteSpace([string]`$m.version) -and -not [string]::IsNullOrWhiteSpace([string]`$m.jarSha256)){return `$m}
  }catch{RLog ('Manifest yoxlaması alınmadı: '+`$_.Exception.Message)}
  return `$null
}
function VersionGreater([string]`$remote,[string]`$local){
  try{return ([version]`$remote -gt [version]`$local)}catch{return (`$remote -ne `$local)}
}
function MarkerSha{
  try{
    if(-not(Test-Path `$marker)){return ''}
    foreach(`$line in Get-Content `$marker -ErrorAction Stop){if(`$line -like 'jarSha256=*'){return (`$line.Substring(10)).Trim().ToLowerInvariant()}}
  }catch{}
  return ''
}
function UpdateNeeded{
  `$m=RemoteManifest
  if(`$null -eq `$m){return `$false}
  `$remote=[string]`$m.version
  if(VersionGreater `$remote `$ExpectedVersion){RLog ('Yeni Agent versiyası tapıldı: '+`$ExpectedVersion+' -> '+`$remote);return `$true}
  if(`$remote -eq `$ExpectedVersion){
    `$localSha=MarkerSha
    `$remoteSha=([string]`$m.jarSha256).Trim().ToLowerInvariant()
    if(-not [string]::IsNullOrWhiteSpace(`$localSha) -and `$remoteSha -ne `$localSha){RLog 'Eyni versiya üçün server JAR hash-i dəyişib; bərpa tələb olunur.';return `$true}
  }
  return `$false
}
function Ensure-RepairCmd{
  if((Test-Path `$repairCmd) -and (Get-Item `$repairCmd).Length -gt 500){return `$true}
  New-Item -ItemType Directory -Force -Path `$repairDir|Out-Null
  for(`$i=1;`$i -le 3;`$i++){try{Remove-Item -Force `$repairCmd -ErrorAction SilentlyContinue;Invoke-WebRequest -UseBasicParsing -TimeoutSec 90 -Uri `$repairUrl -OutFile `$repairCmd;if((Test-Path `$repairCmd) -and (Get-Item `$repairCmd).Length -gt 500){RLog 'Bərpa CMD-si yenidən yükləndi.';return `$true}}catch{RLog ('Repair CMD cəhdi '+`$i+' alınmadı: '+`$_.Exception.Message)};Start-Sleep -Seconds (2*`$i)}
  return `$false
}
function MarkerValue([string]`$key){
  try{if(-not(Test-Path `$marker)){return ''};foreach(`$line in Get-Content `$marker -ErrorAction Stop){`$clean=([string]`$line).TrimStart([char]0xFEFF);if(`$clean.StartsWith(`$key+'=')){return `$clean.Substring(`$key.Length+1).Trim()}}}catch{};return ''
}
function Start-Agent{
  try{
    `$java=MarkerValue 'javaPath';`$origin=MarkerValue 'origin';`$jar=Join-Path `$agentDir 'taxdata-agent.jar'
    if((Test-Path `$java) -and (Test-Path `$jar)){
      `$stdout=Join-Path `$agentDir 'agent-stdout.log';`$stderr=Join-Path `$agentDir 'agent-stderr.log'
      Remove-Item -Force `$stdout,`$stderr -ErrorAction SilentlyContinue
      `$safeJar=`$jar.Replace('"','\"');`$safeOrigin=`$origin.Replace('"','\"')
      `$argumentLine='-Dtaxdata.agent.allowed-origin="'+`$safeOrigin+'" -Dfile.encoding=UTF-8 -jar "'+`$safeJar+'" --server.address=127.0.0.1 --server.port=47631'
      Start-Process -WindowStyle Hidden -FilePath `$java -ArgumentList `$argumentLine -RedirectStandardOutput `$stdout -RedirectStandardError `$stderr -ErrorAction Stop
      RLog 'Agent Java birbaşa başladıldı.';return
    }
  }catch{RLog ('Birbaşa Agent start alınmadı: '+`$_.Exception.Message)}
  if(Test-Path `$start){try{Start-Process -WindowStyle Hidden -FilePath `$powerShellExe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',`$start)}catch{RLog ('PowerShell fallback start alınmadı: '+`$_.Exception.Message)}}
}
function Full-Repair([bool]`$forUpdate=`$false){
  `$tmp=Join-Path `$env:TEMP ('taxdata-agent-repair-'+[Guid]::NewGuid().ToString('N')+'.ps1')
  try{
    for(`$i=1;`$i -le 3;`$i++){
      try{Remove-Item -Force `$tmp -ErrorAction SilentlyContinue;Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 -Headers @{'Cache-Control'='no-cache'} -Uri `$setupUrl -OutFile `$tmp;if((Test-Path `$tmp) -and (Get-Item `$tmp).Length -gt 1000){break}}catch{RLog ('setup.ps1 cəhdi '+`$i+' alınmadı: '+`$_.Exception.Message)}
      Start-Sleep -Seconds (2*`$i)
    }
    if(-not(Test-Path `$tmp)){throw 'Bərpa modulu yüklənmədi.'}
    `$p=Start-Process -WindowStyle Hidden -Wait -PassThru -FilePath `$powerShellExe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',`$tmp)
    if(`$p.ExitCode -ne 0){throw ('Bərpa kodu '+`$p.ExitCode)}
    RLog 'Tam avtomatik bərpa/yeniləmə tamamlandı.'
    if(`$forUpdate){
      Start-Sleep -Seconds 2
      `$s=LocalStatus
      if(`$s -and [string]`$s.version -ne `$ExpectedVersion){RLog ('Agent yeni versiyaya keçdi: '+[string]`$s.version+'. Yeni watchdog başladılacaq.');`$script:restartAfterUpdate=`$true}
    }
  }catch{RLog ('Tam bərpa/yeniləmə alınmadı: '+`$_.Exception.Message)}finally{Remove-Item -Force `$tmp -ErrorAction SilentlyContinue}
}
try{while(`$true){
  foreach(`$g in @(`$guard,`$outer)){if(Test-Path `$g){try{if(((Get-Date)-(Get-Item `$g).LastWriteTime).TotalMinutes -gt 10){Remove-Item -Force `$g -ErrorAction SilentlyContinue}}catch{}}}
  if((Test-Path `$guard) -or (Test-Path `$outer)){Start-Sleep -Seconds 5;continue}
  Ensure-RepairCmd|Out-Null
  if(-not(Ready)){
    Start-Agent;Start-Sleep -Seconds 5
    if(-not(Ready)){RLog 'Agent natamamdır və ya işləmir; tam bərpa başlanır.';Full-Repair `$false;Start-Sleep -Seconds 8}
  }elseif(((Get-Date)-`$lastManifestCheck).TotalSeconds -ge 60){
    `$lastManifestCheck=Get-Date
    if(UpdateNeeded){
      if(AgentBusy){RLog 'Yeni Agent versiyası var, amma aktiv e-Taxes işi bitənədək yeniləmə gözlədilir.'}
      else{RLog 'Agent avtomatik yenilənir...';Full-Repair `$true;if(`$restartAfterUpdate){break};Start-Sleep -Seconds 5}
    }
  }
  Start-Sleep -Seconds 15
}}finally{try{`$mutex.ReleaseMutex()}catch{};`$mutex.Dispose()}
if(`$restartAfterUpdate){
  Start-Sleep -Seconds 2
  try{Start-Process -WindowStyle Hidden -FilePath `$powerShellExe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',`$watchdog)}catch{}
}
"@
  Set-Content -Encoding UTF8 -Path $watchdog -Value $watchdogScript
  try{Unblock-File -LiteralPath $watchdog -ErrorAction SilentlyContinue}catch{}

  New-Item -Path $runKey -Force|Out-Null
  $javaRun=$javaLaunch
  try{$candidateJavaw=Join-Path (Split-Path -Parent $javaCheck) 'javaw.exe';if(Test-Path -LiteralPath $candidateJavaw){$javaRun=$candidateJavaw}}catch{}
  $run='"'+$javaRun+'" -Dtaxdata.agent.allowed-origin='+$Origin+' -Dfile.encoding=UTF-8 -jar "'+$finalJar+'" --server.address=127.0.0.1 --server.port=47631'
  $watchRun='"'+$PowerShellExe+'" -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$watchdog+'"'
  Set-ItemProperty -Path $runKey -Name 'TaxData Agent' -Value $run
  Set-ItemProperty -Path $runKey -Name 'TaxData Agent Watchdog' -Value $watchRun
  # Watchdog əvvəlcədən başladılır; install guard olduğu üçün quraşdırmaya müdaxilə etmir.
  Start-Process -WindowStyle Hidden -FilePath $PowerShellExe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',$watchdog)

  Assert-AgentJar $finalJar
  $finalJavaHome=Split-Path -Parent (Split-Path -Parent $javaCheck);$finalJavaProbe=Get-Java21Probe $finalJavaHome;if(-not $finalJavaProbe.Valid){Show-JavaProbe $finalJavaProbe;throw 'Java 21 yekun yoxlamadan keçmədi.'}
  if(-not(Test-Path $start) -or -not(Test-Path $watchdog) -or -not(Test-Path $repairCmd)){throw 'Quraşdırma komponentlərindən biri çatışmır.'}
  $markerText=@"
version=$ExpectedVersion
jarSha256=$ExpectedJarSha
javaPath=$javaCheck
javaMode=$javaMode
origin=$Origin
completedAt=$([DateTime]::UtcNow.ToString('o'))
"@
  # Marker Java tərəfindən də oxunur. Windows PowerShell 5.1 `Set-Content -Encoding UTF8`
  # fayla BOM əlavə edir və ilk `version` açarını `\uFEFFversion` kimi göstərə bilər.
  # Buna görə marker UTF-8 BOM-suz yazılır.
  [IO.File]::WriteAllText($marker,$markerText,(New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false))

  Start-AgentDirect $javaLaunch $finalJar $Origin | Out-Null
  if(-not(Wait-Process)){Show-AgentStartDiagnostics;throw 'Agent prosesi başladılmadı və ya düzgün versiya cavab vermədi.'}
  if(-not(Wait-Complete)){
    try{
      $diag=Invoke-RestMethod -UseBasicParsing -TimeoutSec 3 -Headers $agentHeaders -Uri 'http://127.0.0.1:47631/api/status'
      $missing=@($diag.missing)
      $componentPairs=@()
      if($diag.components){$diag.components.PSObject.Properties|ForEach-Object{$componentPairs+=($_.Name+'='+[string]$_.Value)}}
      if($missing.Count -gt 0){Write-Host ('Natamam komponentler: '+($missing -join ', ')) -ForegroundColor Yellow;Log ('Natamam komponentler: '+($missing -join ', '))}
      if($componentPairs.Count -gt 0){Write-Host ('Komponent statusu: '+($componentPairs -join '; ')) -ForegroundColor DarkYellow;Log ('Komponent statusu: '+($componentPairs -join '; '))}
    }catch{}
    throw 'Agent bütün komponentləri yoxlamadan keçirmədi.'
  }

  # New TaxData Agent is healthy; only now remove the legacy installation.
  Remove-LegacyAgentFiles
  Drop-Backups;Remove-Safe $stage;Remove-Safe $installGuard
  Log 'Quraşdırma/bərpa tam sağlam vəziyyətdə tamamlandı.'
  Write-Host ''
  Write-Host 'TaxData Agent tam quraşdırıldı: JAR, Java 21, start, watchdog və bərpa CMD-si yoxlanıldı.' -ForegroundColor Green
  Start-Sleep -Milliseconds 500
  exit 0
}catch{
  $m=$_.Exception.Message;Log ('XƏTA: '+$m);Write-Host '';Write-Host ('Quraşdırma tamamlanmadı: '+$m) -ForegroundColor Red
  if($commitStarted){
    Stop-Agent;Remove-Safe $finalJar;Remove-Safe $start;Remove-Safe $watchdog;Remove-Safe $repairCmd;Remove-Safe $marker;if($replaceJre){Remove-Safe $finalJre};Restore-Backups;Restore-RunValues
    if(Test-Path $start){try{Start-Process -WindowStyle Hidden -FilePath $PowerShellExe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',$start)}catch{}}
    if(Test-Path $watchdog){try{Start-Process -WindowStyle Hidden -FilePath $PowerShellExe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',$watchdog)}catch{}}
  }
  Remove-Safe $stage;Remove-Safe $installGuard
  Write-Host 'Yarımçıq fayllar təmizləndi. Saytdakı “Agent bərpa et” ilə yenidən cəhd edə bilərsiniz.' -ForegroundColor Yellow
  exit 1
}finally{Remove-Safe $installGuard;try{$mutex.ReleaseMutex()}catch{};$mutex.Dispose()}
