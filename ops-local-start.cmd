@echo off
REM Lokal-demo: gateway JAR'ni alohida oynada background'da yoqadi va DARHOL qaytadi
REM (ops GatewayControlService output'ni EOF'gacha o'qiydi — 'start ""' detached bo'lgani uchun osilmaydi)
cd /d C:\Users\muxammadali.ergashev\Documents\Projects\qosid-flow
start "qosid-gateway" java -jar target\qosid-flow-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
echo gateway launched
