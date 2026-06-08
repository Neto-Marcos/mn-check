@echo off
setlocal
cd /d "%~dp0"
title MM check

if not defined MMCHECK_ADMIN_PASSWORD (
  set /p "MMCHECK_ADMIN_PASSWORD=Defina a senha inicial do administrador: "
)

where java >nul 2>nul
if errorlevel 1 (
  echo Java nao foi encontrado. Instale o Java 17 ou superior.
  pause
  exit /b 1
)

if not exist "backend\out\MmCheckServer.class" (
  if not exist "backend\out" mkdir "backend\out"
  javac -encoding UTF-8 -d "backend\out" "backend\src\MmCheckServer.java"
  if errorlevel 1 (
    echo Nao foi possivel compilar o MM check.
    pause
    exit /b 1
  )
)

start "" cmd /c "timeout /t 2 /nobreak >nul & start http://127.0.0.1:4173/"
echo MM check iniciado em http://127.0.0.1:4173/
echo Mantenha esta janela aberta durante a apresentacao.
java -cp "backend\out" MmCheckServer
pause
