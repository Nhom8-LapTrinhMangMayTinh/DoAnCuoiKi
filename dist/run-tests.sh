#!/bin/sh
# Chay test don vi + test nang cao (server TLS nhung, du lieu tam). Ket qua ghi ra file .md trong thu muc hien tai.
cd "$(dirname "$0")"
CP="GroupChat-Client.jar:GroupChat-Server.jar:lib/*"
java -Dfile.encoding=UTF-8 -cp "$CP" tests.UnitTests test-results-unit.md || exit 1
java -Dfile.encoding=UTF-8 -Xmx768m -cp "$CP" tests.AdvancedTest test-results-advanced.md
