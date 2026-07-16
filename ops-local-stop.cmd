@echo off
REM Lokal-demo: 8090-portdagi gateway jarayonini o'ldiradi
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue | Select-Object -Expand OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force }"
echo gateway stopped
