@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

set "TAXDATA_VERSION=7.1.2"

echo TaxData %TAXDATA_VERSION% lokal production-test basladilir...
echo Lokal unvan: http://localhost:8080
echo.

echo [0/4] 8080 portu yoxlanilir...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$connections=@(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue);" ^
  "if($connections.Count -eq 0){Write-Host '8080 portu bosdur.' -ForegroundColor Green; exit 0};" ^
  "$pids=@($connections | Select-Object -ExpandProperty OwningProcess -Unique);" ^
  "$unsafe=@();" ^
  "foreach($pidValue in $pids){" ^
  "  $p=Get-CimInstance Win32_Process -Filter ('ProcessId='+$pidValue) -ErrorAction SilentlyContinue;" ^
  "  if(-not $p){continue};" ^
  "  $cmd=[string]$p.CommandLine; $name=[string]$p.Name;" ^
  "  $isTaxData=($cmd -match '(?i)taxdata-server\.jar|az\.gmb\.taxdata\.TaxDataApplication|TaxData_[67]\.|[\/]TaxData[\/]');" ^
  "  if($isTaxData){" ^
  "    Write-Host ('Kohne TaxData prosesi tapildi. PID: '+$pidValue+' - baglanir...') -ForegroundColor Yellow;" ^
  "    Stop-Process -Id $pidValue -Force -ErrorAction Stop;" ^
  "  } else {" ^
  "    $unsafe += [pscustomobject]@{Pid=$pidValue;Name=$name;CommandLine=$cmd};" ^
  "  }" ^
  "};" ^
  "if($unsafe.Count -gt 0){" ^
  "  Write-Host 'XETA: 8080 portunu TaxData olmayan proses tutur. Tehlukesizlik ucun avtomatik baglanmadi:' -ForegroundColor Red;" ^
  "  $unsafe | ForEach-Object { Write-Host ('  PID '+$_.Pid+'  '+$_.Name+'  '+$_.CommandLine) -ForegroundColor Red };" ^
  "  exit 3" ^
  "};" ^
  "$deadline=(Get-Date).AddSeconds(12);" ^
  "do { Start-Sleep -Milliseconds 350; $left=@(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) } while($left.Count -gt 0 -and (Get-Date) -lt $deadline);" ^
  "if($left.Count -gt 0){Write-Host 'XETA: Kohne TaxData prosesi baglandi, amma 8080 portu vaxtinda bosalmadi.' -ForegroundColor Red; exit 4};" ^
  "Write-Host '8080 portu hazirdir.' -ForegroundColor Green; exit 0"
if errorlevel 4 goto port_release_fail
if errorlevel 3 goto foreign_port_busy
if errorlevel 1 goto port_check_fail

echo.
echo [1/4] Java 21 JDK yoxlanilir / lazim olsa avtomatik hazirlanir...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\ensure-build-jdk21.ps1"
if errorlevel 1 goto java_fail

rem JDK yolunu ASCII state faylindan oxumuruq. Istifadeci adinda kiril/Unicode
rem herfleri ola biler. Birbasa Windows-un oz LOCALAPPDATA/JAVA_HOME yollarindan tapiriq.
set "TAXDATA_PRIVATE_JDK=%LOCALAPPDATA%\TaxData\BuildJdk21"
set "TAXDATA_SELECTED_JDK="
if exist "%TAXDATA_PRIVATE_JDK%\bin\java.exe" if exist "%TAXDATA_PRIVATE_JDK%\bin\javac.exe" set "TAXDATA_SELECTED_JDK=%TAXDATA_PRIVATE_JDK%"
if not defined TAXDATA_SELECTED_JDK (
  for /d %%D in ("%TAXDATA_PRIVATE_JDK%\*") do (
    if not defined TAXDATA_SELECTED_JDK if exist "%%~fD\bin\java.exe" if exist "%%~fD\bin\javac.exe" set "TAXDATA_SELECTED_JDK=%%~fD"
  )
)
if not defined TAXDATA_SELECTED_JDK if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" if exist "%JAVA_HOME%\bin\javac.exe" set "TAXDATA_SELECTED_JDK=%JAVA_HOME%"
if not defined TAXDATA_SELECTED_JDK (
  for /f "delims=" %%J in ('where javac.exe 2^>nul') do (
    if not defined TAXDATA_SELECTED_JDK for %%P in ("%%~dpJ..") do set "TAXDATA_SELECTED_JDK=%%~fP"
  )
)
if not defined TAXDATA_SELECTED_JDK goto java_fail
set "JAVA_HOME=%TAXDATA_SELECTED_JDK%"
if not exist "%JAVA_HOME%\bin\java.exe" goto java_fail
if not exist "%JAVA_HOME%\bin\javac.exe" goto java_fail
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo TaxData build JDK yolu: %JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
if errorlevel 1 goto java_fail
"%JAVA_HOME%\bin\javac.exe" -version
if errorlevel 1 goto java_fail

echo.
echo [2/4] Server + TaxData Local Agent tam build edilir...
call gradlew.bat clean bootJar -x test --no-daemon --max-workers=1
if errorlevel 1 goto build_fail
if not exist "build\libs\taxdata-server.jar" goto build_fail
if not exist "build\libs\taxdata-agent.jar" goto build_fail

echo.
echo [3/4] Agent artefakti yoxlanilir...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$a=Get-Item -LiteralPath '%CD%\build\libs\taxdata-agent.jar';" ^
  "$s=Get-Item -LiteralPath '%CD%\build\libs\taxdata-server.jar';" ^
  "if($a.Length -le 100000 -or $s.Length -le 100000){throw 'Build JAR fayllari natamamdir.'};" ^
  "Write-Host ('Agent JAR: '+$a.Length+' bayt') -ForegroundColor Green;" ^
  "Write-Host ('Server JAR: '+$s.Length+' bayt') -ForegroundColor Green"
if errorlevel 1 goto build_fail

echo.
echo [4/4] TaxData http://localhost:8080 unvaninda basladilir...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\start-server-with-env.ps1" -JavaExe "%JAVA_HOME%\bin\java.exe" -ServerJar "%CD%\build\libs\taxdata-server.jar" -AgentJar "%CD%\build\libs\taxdata-agent.jar" -ProjectDir "%CD%"
if errorlevel 1 goto server_fail
exit /b 0

:foreign_port_busy
echo.
echo 8080 portunu basqa proqram tutur. TaxData hemin prosesi avtomatik baglamadi.
echo Yuxarida gosterilen prosesi yoxlayin veya TaxData-ni basqa portda basladin.
pause
exit /b 3

:port_release_fail
echo.
echo XETA: Kohne TaxData prosesi dayandirildi, amma 8080 portu hele de mesguldur.
echo Bir nece saniye sonra START_WINDOWS.bat faylini yeniden acin.
pause
exit /b 4

:port_check_fail
echo.
echo XETA: 8080 port yoxlamasi icra olunmadi.
echo PowerShell / Windows networking servislerini yoxlayin.
pause
exit /b 5

:java_fail
echo.
echo XETA: TaxData Java 21 JDK-ni hazirlaya bilmedi.
echo Administrator huququ teleb olunmur. Internet elaqesini ve Windows PowerShell-i yoxlayin.
echo Portable JDK yolu: %%LOCALAPPDATA%%\TaxData\BuildJdk21
pause
exit /b 6

:build_fail
echo.
echo XETA: Tam TaxData build alinmadi.
echo Server qesden basladilmadi. Agent artefakti olmadan localhost acilmayacaq.
echo Java 21 JDK artiq TaxData terefinden avtomatik idare olunur; yuxaridaki Gradle xetasina baxin.
pause
exit /b 1

:server_fail
echo.
echo XETA: TaxData server dayandi ve ya baslamadi.
pause
exit /b 1
