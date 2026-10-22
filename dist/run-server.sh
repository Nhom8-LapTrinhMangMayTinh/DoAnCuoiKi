#!/bin/sh
cd "$(dirname "$0")"
exec java -Dfile.encoding=UTF-8 -jar GroupChat-Server.jar "$@"
