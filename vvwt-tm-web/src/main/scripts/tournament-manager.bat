@echo off
REM Tournament Manager V1 — Windows launcher script
REM Story E02S05 | DEC-15: jlink primary distribution format
REM DEC-68 (E55S15): TM_DATA_DIR is the single operator override for the data root.
REM
REM Usage:
REM   bin\tournament-manager.bat [--verbose|-v] [spring-boot-args...]
REM
REM Environment variables:
REM   TM_DATA_DIR      Root directory for all Tournament Manager data.
REM                    Default: %USERPROFILE%\.tournament-manager
REM                    All per-purpose subdirectories (db\, audio\, photos\, etc.) reside here.
REM   TM_DB_PATH       Path to H2 database file (no .mv.db extension).
REM                    Default: %TM_DATA_DIR%\db\tm (derived from TM_DATA_DIR).
REM                    Overrides TM_DATA_DIR for the database location only.
REM   TM_SERVER_PORT   HTTP server port. Default: 8080
REM   TM_JVM_XMS       JVM initial heap. Default: 64m
REM   TM_JVM_XMX       JVM maximum heap. Default: 512m

setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "INSTALL_DIR=%SCRIPT_DIR%.."
set "RUNTIME_JAVA=%INSTALL_DIR%\runtime\bin\java.exe"
set "LIB_DIR=%INSTALL_DIR%\lib"
set "VERSION=@project.version@"

REM AC8: Guard -- bundled JRE must exist
if not exist "%RUNTIME_JAVA%" (
    echo Error: JRE runtime not found at %RUNTIME_JAVA% -- archive may be corrupt or incomplete
    exit /b 1
)

REM Parse flags: --verbose/-v consumed here; all other args forwarded to Spring Boot.
REM AC10: verbose; AC11: no "set" or env dump.
set "VERBOSE=0"
set "EXTRA_ARGS="

:parse_loop
if "%~1"=="" goto :end_parse
if "%~1"=="--verbose" ( set "VERBOSE=1" & shift & goto :parse_loop )
if "%~1"=="-v"        ( set "VERBOSE=1" & shift & goto :parse_loop )
set "EXTRA_ARGS=!EXTRA_ARGS! %~1"
shift
goto :parse_loop
:end_parse

REM Resolved configuration (not raw env -- AC11)
REM DEC-68 (E55S15): TM_DATA_DIR is the single root; TM_DB_PATH overrides DB only.
if not defined TM_JVM_XMS    set "TM_JVM_XMS=64m"
if not defined TM_JVM_XMX    set "TM_JVM_XMX=512m"
if not defined TM_SERVER_PORT set "TM_SERVER_PORT=8080"
if not defined TM_DATA_DIR    set "TM_DATA_DIR=%USERPROFILE%\.tournament-manager"
if not defined TM_DB_PATH     set "TM_DB_PATH=%TM_DATA_DIR%\db\tm"

REM AC10: verbose output or single-line banner.
REM AC11: only resolved config values printed -- no "set" dump.
if "%VERBOSE%"=="1" (
    echo Tournament Manager %VERSION% -- verbose mode
    echo   Install dir : %INSTALL_DIR%
    echo   JRE path    : %RUNTIME_JAVA%
    echo   Lib dir     : %LIB_DIR%
    echo   Data dir    : %TM_DATA_DIR%
    echo   DB path     : %TM_DB_PATH%
    echo   Server port : %TM_SERVER_PORT%
    echo   Heap min    : %TM_JVM_XMS%
    echo   Heap max    : %TM_JVM_XMX%
    echo.
) else (
    echo Starting Tournament Manager %VERSION% on port %TM_SERVER_PORT%, DB path %TM_DB_PATH%
)

REM Launch via bundled JRE.
REM AC4: relative path via %RUNTIME_JAVA%; pass-through CLI args via %EXTRA_ARGS%.
REM AC9: port-in-use error surfaces from Spring Boot / JVM stderr naturally.
"%RUNTIME_JAVA%" -Xms%TM_JVM_XMS% -Xmx%TM_JVM_XMX% -cp "%LIB_DIR%\*" de.vvwt.tm.TournamentManagerApplication%EXTRA_ARGS%

endlocal
