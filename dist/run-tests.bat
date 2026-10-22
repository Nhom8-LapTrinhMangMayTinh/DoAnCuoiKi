@echo off
cd /d "%~dp0"
chcp 65001 >nul
set CP=GroupChat-Client.jar;GroupChat-Server.jar;lib\*
java -Dfile.encoding=UTF-8 -cp "%CP%" tests.UnitTests test-results-unit.md || exit /b 1
java -Dfile.encoding=UTF-8 -Xmx768m -cp "%CP%" tests.AdvancedTest test-results-advanced.md
