@echo off
setlocal enabledelayedexpansion

:: Get the directory where run.bat is located
set "DIR=%~dp0"
cd /d "%DIR%"

echo Pulling latest changes from fork (origin main)...
git pull --rebase origin main || echo Warning: git pull failed, running with local version

set "JAR_PATH=forge-gui-mobile-dev\target\forge-gui-mobile-dev-2.0.14-SNAPSHOT-jar-with-dependencies.jar"
set "REBUILD=false"
set "FORCE=false"
set "SET_LANG="
set "DEBUG=false"
set "CHEATS=false"
set "PREFS_FILE=%APPDATA%\Forge\preferences\forge.preferences"

:: Parse arguments and preserve other flags
set "ARGS="
:parse_args
if "%~1"=="" goto done_args
set "is_control_flag=false"
if "%~1"=="--force" (set "FORCE=true" & set "is_control_flag=true")
if "%~1"=="-f" (set "FORCE=true" & set "is_control_flag=true")
if "%~1"=="--english" (set "SET_LANG=en-US" & set "is_control_flag=true")
if "%~1"=="-en" (set "SET_LANG=en-US" & set "is_control_flag=true")
if "%~1"=="-e" (set "SET_LANG=en-US" & set "is_control_flag=true")
if "%~1"=="--german" (set "SET_LANG=de-DE" & set "is_control_flag=true")
if "%~1"=="-de" (set "SET_LANG=de-DE" & set "is_control_flag=true")
if "%~1"=="-g" (set "SET_LANG=de-DE" & set "is_control_flag=true")
if "%~1"=="--debug" (set "DEBUG=true" & set "is_control_flag=true")
if "%~1"=="-d" (set "DEBUG=true" & set "is_control_flag=true")
if "%~1"=="--cheats" (set "CHEATS=true" & set "is_control_flag=true")
if "%~1"=="-c" (set "CHEATS=true" & set "is_control_flag=true")

if "!is_control_flag!"=="false" (
    set "ARGS=!ARGS! %1"
)
shift
goto parse_args
:done_args

:: Function to update pref using PowerShell
if exist "%PREFS_FILE%" (
    if not "%SET_LANG%"=="" (
        echo Setting language to %SET_LANG%...
        powershell -Command "$file='%PREFS_FILE%'; $content = Get-Content $file; if ($content -match '^UI_LANGUAGE=') { $content = $content -replace '^UI_LANGUAGE=.*', 'UI_LANGUAGE=%SET_LANG%' } else { $content += 'UI_LANGUAGE=%SET_LANG%' }; $content | Set-Content $file"
    )
    if "%DEBUG%"=="true" (
        echo Enabling developer mode...
        powershell -Command "$file='%PREFS_FILE%'; $content = Get-Content $file; if ($content -match '^DEV_MODE_ENABLED=') { $content = $content -replace '^DEV_MODE_ENABLED=.*', 'DEV_MODE_ENABLED=true' } else { $content += 'DEV_MODE_ENABLED=true' }; $content | Set-Content $file"
    ) else (
        powershell -Command "$file='%PREFS_FILE%'; $content = Get-Content $file; if ($content -match '^DEV_MODE_ENABLED=') { $content = $content -replace '^DEV_MODE_ENABLED=.*', 'DEV_MODE_ENABLED=false' } else { $content += 'DEV_MODE_ENABLED=false' }; $content | Set-Content $file"
    )
    if "%CHEATS%"=="true" (
        echo Enabling cheats...
        powershell -Command "$file='%PREFS_FILE%'; $content = Get-Content $file; if ($content -match '^CHEATS_ENABLED=') { $content = $content -replace '^CHEATS_ENABLED=.*', 'CHEATS_ENABLED=true' } else { $content += 'CHEATS_ENABLED=true' }; $content | Set-Content $file"
    ) else (
        powershell -Command "$file='%PREFS_FILE%'; $content = Get-Content $file; if ($content -match '^CHEATS_ENABLED=') { $content = $content -replace '^CHEATS_ENABLED=.*', 'CHEATS_ENABLED=false' } else { $content += 'CHEATS_ENABLED=false' }; $content | Set-Content $file"
    )
)

:: Rebuild checks
if "%FORCE%"=="true" (
    set "REBUILD=true"
) else if not exist "%JAR_PATH%" (
    set "REBUILD=true"
)

if "%REBUILD%"=="true" (
    echo Building project...
    call mvn package -pl forge-gui-mobile-dev -am -DskipTests
    if %errorlevel% neq 0 (
        echo Error: Build failed. Exiting.
        pause
        exit /b %errorlevel%
    )
)

:: Run Java
set "JAVA_OPTS=-Xmx32768m -Xss8m"
if "%DEBUG%"=="true" (
    set "JAVA_OPTS=%JAVA_OPTS% -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
)

set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/java.beans=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/javax.swing.border=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/javax.swing.event=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/sun.swing=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/java.awt.image=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/java.awt.color=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/sun.awt.image=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/javax.swing=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/java.awt=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.util=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.lang=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.lang.reflect=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.text=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.desktop/java.awt.font=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/jdk.internal.misc=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/sun.nio.ch=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.nio=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.math=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.util.concurrent=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.net=ALL-UNNAMED"
set "JAVA_OPTS=%JAVA_OPTS% -Dio.netty.tryReflectionSetAccessible=true"
set "JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8"

cd forge-gui-mobile-dev
java %JAVA_OPTS% -jar target/forge-gui-mobile-dev-2.0.14-SNAPSHOT-jar-with-dependencies.jar %ARGS%
