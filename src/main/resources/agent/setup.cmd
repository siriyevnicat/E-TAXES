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
if not exist "%LOCALAPPDATA%\TaxData" mkdir "%LOCALAPPDATA%\TaxData" >nul 2>&1
echo active>"%LOCALAPPDATA%\TaxData\.agent-installer.lock"
set "PSFILE=%TEMP%\taxdata-agent-bootstrap-%RANDOM%.ps1"
set /a GETTRY=0
:get_setup
set /a GETTRY+=1
echo Berpa modulu yuklenir... cehd %GETTRY%/4
echo URL: %BOOTSTRAP_URL%

del /q "%PSFILE%" >nul 2>&1

rem Method 1: Windows 10/11 curl.exe. It avoids Windows PowerShell proxy quirks.
where curl.exe >nul 2>&1
if not errorlevel 1 (
    curl.exe --fail --location --silent --show-error --connect-timeout 15 --max-time 120 --header "Cache-Control: no-cache" --header "Accept: text/plain" "%BOOTSTRAP_URL%" --output "%PSFILE%"
    if not errorlevel 1 call :validate_bootstrap
    if not errorlevel 1 goto run_setup
    echo curl.exe ile berpa modulu alinmadi. PowerShell fallback yoxlanilir...
    del /q "%PSFILE%" >nul 2>&1
)

rem Method 2: PowerShell fallback. For local 127.0.0.1 disable the system proxy.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url=$env:BOOTSTRAP_URL; try { $wc=New-Object System.Net.WebClient; if($url -match '^http://127\\.0\\.0\\.1(?::\\d+)?/'){ $wc.Proxy=[System.Net.GlobalProxySelection]::GetEmptyWebProxy() }; $wc.Headers['Cache-Control']='no-cache'; $wc.Headers['Accept']='text/plain'; $wc.DownloadFile($url,$env:PSFILE); if(-not(Test-Path $env:PSFILE)){throw 'Bootstrap fayli yaranmadi.'}; exit 0 } catch { Write-Host ('PowerShell yukleme xetasi: '+$_.Exception.Message+'  URL: '+$url) -ForegroundColor Red; exit 1 }"
if not errorlevel 1 call :validate_bootstrap
if not errorlevel 1 goto run_setup

del /q "%PSFILE%" >nul 2>&1
if %GETTRY% GEQ 4 goto fail_download
timeout /t 3 /nobreak >nul
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
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PSFILE%"
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" goto success
if %TRY% GEQ 3 goto failcode
echo Natamam cehd temizlendi. 4 saniyeden sonra yeniden yoxlanacaq...
timeout /t 4 /nobreak >nul
goto install_retry

:success
del /q "%LOCALAPPDATA%\TaxData\.agent-installer.lock" >nul 2>&1
del /q "%PSFILE%" >nul 2>&1
echo.
echo Hazirdir: Agent butun komponentlerle birlikde yoxlamadan kecdi.
timeout /t 3 /nobreak >nul
exit /b 0

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
