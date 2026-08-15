@echo off
setlocal
rem ============================================================
rem  CS2 DMA Radar Client - prepare DMA connection libraries
rem
rem  - FTD3XX.dll (FTDI USB3 driver for the DMA card) is copied
rem    from the DMA tool folder (C:\Users\Salam\Desktop\DMA).
rem  - vmm.dll / leechcore.dll are the STANDARD MemProcFS/LeechCore
rem    builds shipped in .\vmm, matched to the client's JNA bindings.
rem    Do NOT replace them with card-vendor DLLs: older/custom builds
rem    crash the client at VMMDLL_ConfigGet ("Invalid memory access").
rem ============================================================
set "SRC=C:\Users\Salam\Desktop\DMA"

if not exist "%~dp0vmm" mkdir "%~dp0vmm"

if exist "%SRC%\FTD3XX.dll" (
    copy /Y "%SRC%\FTD3XX.dll" "%~dp0vmm\" >nul
    echo [OK] FTD3XX.dll copied from %SRC%
) else (
    echo [X] FTD3XX.dll not found in %SRC% - edit SRC at the top of this file.
)

if not exist "%~dp0vmm\vmm.dll"      echo [X] vmm.dll missing in .\vmm - restore the standard MemProcFS build.
if not exist "%~dp0vmm\leechcore.dll" echo [X] leechcore.dll missing in .\vmm - restore the standard LeechCore build.

echo.
echo Current .\vmm contents:
dir /b "%~dp0vmm"
echo.
echo Next step: java -jar target\cs2-dma-client.jar  (see run.bat)
