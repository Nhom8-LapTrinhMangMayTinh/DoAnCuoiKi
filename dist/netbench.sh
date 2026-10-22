#!/bin/sh
# Do RTT / thong luong / ket noi lai / nhieu client toi 1 hoac 2 server. Vi du:
#   ./netbench.sh 192.168.1.10 5555 --label LAN --compare 100.101.102.103 --label2 Tailscale --out so-sanh.md
cd "$(dirname "$0")"
exec java -Dfile.encoding=UTF-8 -cp "GroupChat-Client.jar:GroupChat-Server.jar:lib/*" tests.NetBench "$@"
