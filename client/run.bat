@echo off
setlocal
rem ============================================================
rem  CS2 DMA Radar Client - start (Windows)
rem  Requires: JDK 17+ and a built jar (mvnw package)
rem ============================================================
cd /d "%~dp0"
java -jar target\cs2-dma-client.jar
pause
