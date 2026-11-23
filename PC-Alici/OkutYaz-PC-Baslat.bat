@echo off
chcp 65001 >nul
title Okut-Yaz - PC alici
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0OkutYaz-PC.ps1"
pause
