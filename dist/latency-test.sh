#!/bin/sh
cd "$(dirname "$0")"
exec java -Dfile.encoding=UTF-8 -cp "GroupChat-Client.jar:GroupChat-Server.jar:lib/*" tests.LatencyTest "$@"
