@echo off
setlocal

set "ROOT=%~dp0"
set "OUT=%ROOT%out"
set "SRC=%ROOT%src\main\java"
set "MAIN=com.codex.pomodorostats.desktop.PomodoroDesktop"

if not exist "%OUT%" mkdir "%OUT%"
if not exist "%OUT%\classes" mkdir "%OUT%\classes"

javac -encoding UTF-8 -source 8 -target 8 -d "%OUT%\classes" "%SRC%\com\codex\pomodorostats\desktop\PomodoroDesktop.java"
if errorlevel 1 exit /b 1

jar --create --file "%OUT%\PomodoroStatsDesktop.jar" --main-class %MAIN% -C "%OUT%\classes" .
if errorlevel 1 exit /b 1

echo Built %OUT%\PomodoroStatsDesktop.jar
