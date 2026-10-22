@echo off
cd /d "%~dp0"
chcp 65001 >nul
java -Dfile.encoding=UTF-8 -cp "GroupChat-Client.jar;GroupChat-Server.jar;lib\*" tests.NetBench %*
