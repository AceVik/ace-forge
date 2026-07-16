@echo off
echo Pulling and rebasing latest changes from Card-Forge original repository (upstream master)...
git pull --rebase upstream master
if %errorlevel% neq 0 (
    echo.
    echo Error: git pull --rebase failed!
    pause
    exit /b %errorlevel%
)
echo.
echo Sync completed successfully.
pause
