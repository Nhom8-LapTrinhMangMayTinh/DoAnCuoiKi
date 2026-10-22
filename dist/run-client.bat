@echo off
cd /d "%~dp0"
start "" javaw -Dfile.encoding=UTF-8 -jar GroupChat-Client.jar %*
