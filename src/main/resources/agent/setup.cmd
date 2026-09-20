@echo off
chcp 65001 >nul
setlocal EnableExtensions
cls
echo TaxData Local Agent qurasdirma ve berpa modulu __AGENT_VERSION__
echo.
echo Bu modul Agent, Java 21, start skripti, watchdog ve berpa fayllarini yoxlayir.
echo Catismayan ve ya xarab fayl varsa temiz nusxeni yeniden yukleyir.
echo Butun yoxlamalar kecmeden "Agent hazirdir" veziyyeti yaranmir.
echo Administrator huququ teleb olunmur.
echo.
set "TAXDATA_BASE=__TAXDATA_BASE__"
set "TAXDATA_DOWNLOAD_BASE=%TAXDATA_BASE%"
rem Windows local proxy/name-resolution problems are avoided for localhost.
set "TAXDATA_DOWNLOAD_BASE=%TAXDATA_DOWNLOAD_BASE:localhost=127.0.0.1%"
set "BOOTSTRAP_URL=%TAXDATA_DOWNLOAD_BASE%/agent/bootstrap"

rem PowerShell PATH-dan silinse bele Agent ozunu berpa ede bilsin.
rem 9009 = komanda tapilmadi. Standart Windows yollarini birbasa yoxlayiriq.
set "PSEXE="
if exist "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" set "PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not defined PSEXE if exist "%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe" set "PSEXE=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"
if not defined PSEXE if exist "%SystemRoot%\SysWOW64\WindowsPowerShell\v1.0\powershell.exe" set "PSEXE=%SystemRoot%\SysWOW64\WindowsPowerShell\v1.0\powershell.exe"
if not defined PSEXE if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "PSEXE=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not defined PSEXE for /f "delims=" %%P in ('where powershell.exe 2^>nul') do if not defined PSEXE set "PSEXE=%%P"
if not defined PSEXE for /f "delims=" %%P in ('where pwsh.exe 2^>nul') do if not defined PSEXE set "PSEXE=%%P"
if not defined PSEXE goto fail_powershell
for %%P in ("%PSEXE%") do set "PATH=%%~dpP;%PATH%"
echo PowerShell tapildi: %PSEXE%

if not exist "%LOCALAPPDATA%\TaxData" mkdir "%LOCALAPPDATA%\TaxData" >nul 2>&1
echo active>"%LOCALAPPDATA%\TaxData\.agent-installer.lock"
set "PSFILE=%TEMP%\taxdata-agent-bootstrap-%RANDOM%.ps1"
set /a GETTRY=0
:get_setup
set /a GETTRY+=1
echo Berpa modulu yuklenir... cehd %GETTRY%/3
echo URL: %BOOTSTRAP_URL%

del /q "%PSFILE%" >nul 2>&1

rem Method 1: Windows 10/11 curl.exe. It avoids Windows PowerShell proxy quirks.
where curl.exe >nul 2>&1
if not errorlevel 1 (
    curl.exe --fail --location --silent --show-error --retry 2 --retry-delay 1 --connect-timeout 7 --max-time 60 --header "Cache-Control: no-cache" --header "Accept: text/plain" "%BOOTSTRAP_URL%" --output "%PSFILE%"
    if not errorlevel 1 call :validate_bootstrap
    if not errorlevel 1 goto run_setup
    echo curl.exe ile berpa modulu alinmadi. PowerShell fallback yoxlanilir...
    del /q "%PSFILE%" >nul 2>&1
)

rem Method 2: PowerShell fallback. For local 127.0.0.1 disable the system proxy.
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url=$env:BOOTSTRAP_URL; try { $wc=New-Object System.Net.WebClient; if($url -match '^http://127\\.0\\.0\\.1(?::\\d+)?/'){ $wc.Proxy=[System.Net.GlobalProxySelection]::GetEmptyWebProxy() }; $wc.Headers['Cache-Control']='no-cache'; $wc.Headers['Accept']='text/plain'; $wc.DownloadFile($url,$env:PSFILE); if(-not(Test-Path $env:PSFILE)){throw 'Bootstrap fayli yaranmadi.'}; exit 0 } catch { Write-Host ('PowerShell yukleme xetasi: '+$_.Exception.Message+'  URL: '+$url) -ForegroundColor Red; exit 1 }"
if not errorlevel 1 call :validate_bootstrap
if not errorlevel 1 goto run_setup

del /q "%PSFILE%" >nul 2>&1
if %GETTRY% GEQ 3 goto fail_download
timeout /t 1 /nobreak >nul
goto get_setup

:validate_bootstrap
if not exist "%PSFILE%" exit /b 1
for %%S in ("%PSFILE%") do set "PSSIZE=%%~zS"
if not defined PSSIZE exit /b 1
if %PSSIZE% LSS 1000 exit /b 1
findstr /C:"taxdata-agent.jar" "%PSFILE%" >nul 2>&1
if errorlevel 1 exit /b 1
findstr /C:"__AGENT_VERSION__" "%PSFILE%" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0

:run_setup
set /a TRY=0
:install_retry
set /a TRY+=1
echo.
echo TaxData Agent yoxlanir ve tamamlanir... cehd %TRY%/3
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%PSFILE%"
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" goto success
if %TRY% GEQ 3 goto failcode
echo Natamam cehd temizlendi. 1 saniyeden sonra yeniden yoxlanacaq...
timeout /t 1 /nobreak >nul
goto install_retry

:success
del /q "%LOCALAPPDATA%\TaxData\.agent-installer.lock" >nul 2>&1
del /q "%PSFILE%" >nul 2>&1
echo.
echo Hazirdir: Agent butun komponentlerle birlikde yoxlamadan kecdi.
timeout /t 1 /nobreak >nul
exit /b 0

:fail_powershell
del /q "%LOCALAPPDATA%\TaxData\.agent-installer.lock" >nul 2>&1
del /q "%PSFILE%" >nul 2>&1
echo.
echo PowerShell tapilmadi. Agent berpa modulu PowerShell olmadan isleye bilmir.
echo Yoxlanilan standart yollar: System32, Sysnative, SysWOW64 ve PowerShell 7.
echo Windows PowerShell fayli movcuddursa, qurasdiricini yeniden acin; PATH teleb olunmur.
timeout /t 8 /nobreak >nul
exit /b 2

:fail_download
del /q "%LOCALAPPDATA%\TaxData\.agent-installer.lock" >nul 2>&1
del /q "%PSFILE%" >nul 2>&1
echo.
echo Berpa modulu yuklenmedi.
echo Yoxlanilan URL: %BOOTSTRAP_URL%
echo Brauzerde bu unvani acin. Metn acilmirsa server endpointi hazir deyil.
echo Saytdaki "Agenti berpa et" duymesinden yeni qurasdiricini endirin.
timeout /t 8 /nobreak >nul
exit /b 1

:failcode
del /q "%LOCALAPPDATA%\TaxData\.agent-installer.lock" >nul 2>&1
del /q "%PSFILE%" >nul 2>&1
echo Qurasdirma 3 cehdden sonra tamamlanmadi. Son xeta kodu: %RC%
echo Yarimciq fayllar temizlenib; saytdaki Agent berpa et duymesinden yeniden cehd edin.
timeout /t 8 /nobreak >nul
exit /b %RC%
