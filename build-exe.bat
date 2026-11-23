@echo off
setlocal EnableDelayedExpansion
REM ------------------------------------------------------------------
REM  ERD Studio - Windows build
REM    build-exe.bat             portable app  -> target\app\ERD Studio\ERD Studio.exe
REM    build-exe.bat installer   Setup.exe     -> target\installer\ERD-Studio-Setup-<version>.exe
REM                                              + SHA256SUMS.txt (needed by the auto-updater)
REM  Needs: JDK 21+ (jpackage) and Maven on PATH; the installer also needs WiX Toolset 3.x on PATH.
REM  The version comes from pom.xml (<version>), nothing else to edit.
REM ------------------------------------------------------------------

where jpackage >nul 2>nul || (echo [!] jpackage not found. Install JDK 21+ and add its bin folder to PATH. & exit /b 1)
where mvn >nul 2>nul || (echo [!] Maven not found on PATH. & exit /b 1)

for /f "usebackq delims=" %%v in (`powershell -NoProfile -Command "([xml](Get-Content pom.xml)).project.version"`) do set APP_VERSION=%%v
if "%APP_VERSION%"=="" (echo [!] Could not read the version from pom.xml & exit /b 1)
echo ERD Studio %APP_VERSION%

set TYPE=app-image
set DEST=target\app
if /i "%1"=="installer" (
    set TYPE=exe
    set DEST=target\installer
    where candle >nul 2>nul || (
        echo [!] WiX Toolset 3 not found. Install WiX 3.14 and add "C:\Program Files ^(x86^)\WiX Toolset v3.14\bin" to PATH.
        exit /b 1
    )
)

echo === 1/3  Building fat jar ===
call mvn -q -B clean package
if errorlevel 1 (echo [!] Maven build failed & exit /b 1)

echo === 2/3  Preparing input ===
if exist target\dist rmdir /s /q target\dist
mkdir target\dist
copy /y target\erd-studio.jar target\dist\ >nul

set MODULES=java.base,java.desktop,java.logging,java.prefs,java.sql,java.xml,java.naming,java.management,java.net.http,java.security.jgss,java.security.sasl,jdk.crypto.ec,jdk.unsupported,jdk.unsupported.desktop,jdk.charsets
set COMMON=--name "ERD Studio" --app-version %APP_VERSION% --vendor "Arvin Saghafi" --description "Database diagram designer" ^
  --copyright "Arvin Saghafi" --input target\dist --main-jar erd-studio.jar --main-class com.erdstudio.Launcher ^
  --icon packaging\erd-studio.ico --add-modules %MODULES% ^
  --java-options "-Xmx1024m" --java-options "-Dfile.encoding=UTF-8"

echo === 3/3  jpackage (%TYPE%) ===
if exist "%DEST%" rmdir /s /q "%DEST%"

if /i "%TYPE%"=="exe" (
    REM  The upgrade UUID must NEVER change: it is how a new Setup.exe finds and replaces the old version.
    jpackage --type exe %COMMON% --dest "%DEST%" ^
      --win-upgrade-uuid 5a6d432b-c1b4-48a0-89a9-ffdf9bd96a7c ^
      --win-per-user-install --win-menu --win-menu-group "ERD Studio" --win-shortcut ^
      --file-associations packaging\erd-file.properties ^
      --about-url https://github.com/eros-codes/ERD-Studio ^
      --win-update-url https://github.com/eros-codes/ERD-Studio/releases
    if errorlevel 1 (echo [!] jpackage failed & exit /b 1)
    pushd "%DEST%"
    ren "ERD Studio-%APP_VERSION%.exe" "ERD-Studio-Setup-%APP_VERSION%.exe"
    powershell -NoProfile -Command "$h=(Get-FileHash -Algorithm SHA256 'ERD-Studio-Setup-%APP_VERSION%.exe').Hash.ToLower(); Set-Content -Encoding ascii SHA256SUMS.txt ($h + '  ERD-Studio-Setup-%APP_VERSION%.exe')"
    popd
) else (
    jpackage --type app-image %COMMON% --dest "%DEST%"
    if errorlevel 1 (echo [!] jpackage failed & exit /b 1)
)

echo.
echo Done:  %DEST%
if /i "%TYPE%"=="app-image" echo Run:   "%DEST%\ERD Studio\ERD Studio.exe"   (copy the whole "ERD Studio" folder anywhere)
if /i "%TYPE%"=="exe" echo Installer: %DEST%\ERD-Studio-Setup-%APP_VERSION%.exe   (+ SHA256SUMS.txt for the GitHub release)
endlocal
