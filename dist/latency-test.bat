@echo off
REM Do do tre: latency-test.bat <IP-server> <port> <so-lan>
cd /d "%~dp0"
java -Dfile.encoding=UTF-8 -cp "GroupChat-Client.jar;GroupChat-Server.jar;lib/*" tests.LatencyTest %*
pause
