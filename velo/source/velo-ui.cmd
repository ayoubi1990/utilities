@echo off
rem Starts the velo desktop app without a console window.
start "" javaw -jar "%~dp0velo.jar" %*
