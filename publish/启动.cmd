@echo off
chcp 65001 >nul
cd /d %~dp0
echo 点检系统启动中: http://localhost:8800
echo 关闭此窗口即可停止服务
webapi.exe --urls=http://0.0.0.0:8800
pause
