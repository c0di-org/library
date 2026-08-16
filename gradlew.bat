@echo off
setlocal
set VERSION=9.5.0
set ROOT=%USERPROFILE%\.gradle\library-bootstrap
set GRADLE_HOME=%ROOT%\gradle-%VERSION%
set ZIP=%ROOT%\gradle-%VERSION%-bin.zip
set URL=https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip
if exist "%GRADLE_HOME%\bin\gradle.bat" goto run
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '%ROOT%' | Out-Null; Invoke-WebRequest -UseBasicParsing '%URL%' -OutFile '%ZIP%'; Expand-Archive -Force '%ZIP%' '%ROOT%'; Remove-Item '%ZIP%'"
:run
call "%GRADLE_HOME%\bin\gradle.bat" %*
endlocal
