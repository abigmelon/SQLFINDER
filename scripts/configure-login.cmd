@echo off
rem 登录初始化
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0configure-login.ps1"
if errorlevel 1 pause
