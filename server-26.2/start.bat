@echo off
rem Builds the runnable server folder from this template and starts it.
rem Running this file is the only step needed - the server folder is generated
rem and can be deleted at any time.
rem
rem Paper reads server.properties and config\ during bootstrap, and plugins read
rem their own configs while they load, both before Interconnect can do anything.
rem Everything is therefore deployed here, while the JVM is not running yet.
cd /d "%~dp0"
set "DEST=..\server"

if not exist "%DEST%" mkdir "%DEST%"
if not exist "%DEST%\plugins" mkdir "%DEST%\plugins"

rem Server jar and EULA: seed once.
for %%F in (paper-26.2-121.jar eula.txt) do (
    if not exist "%DEST%\%%F" if exist "%%F" copy "%%F" "%DEST%\%%F" >nul
)

rem Config: this folder is the source of truth, so overwrite.
for %%F in (server.properties bukkit.yml spigot.yml commands.yml) do (
    if exist "%%F" copy /Y "%%F" "%DEST%\%%F" >nul
)
if exist "config" xcopy "config\*" "%DEST%\config\" /E /Y /I >nul

rem Plugin jars and their configs. Interconnect syncs these as well, but only
rem after Paper has scanned plugins and after plugins like LuckPerms have read
rem their config, which costs a restart on a freshly created server folder.
rem Copying them here means one launch is enough.
if exist "..\plugins-26.2" xcopy "..\plugins-26.2\*" "%DEST%\plugins\" /E /Y /I >nul

rem Anything shipped in this template wins over plugins-26.2.
if exist "plugins" xcopy "plugins\*" "%DEST%\plugins\" /E /Y /I >nul

rem Runtime state: seed only when missing, so /op and /whitelist survive.
for %%F in (ops.json whitelist.json banned-players.json banned-ips.json) do (
    if not exist "%DEST%\%%F" if exist "%%F" copy "%%F" "%DEST%\%%F" >nul
)

cd /d "%DEST%"
java -Xms4096M -Xmx4096M -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+ParallelRefProcEnabled -XX:+PerfDisableSharedMem -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1HeapRegionSize=8M -XX:G1HeapWastePercent=5 -XX:G1MaxNewSizePercent=40 -XX:G1MixedGCCountTarget=4 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1NewSizePercent=30 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -XX:MaxGCPauseMillis=200 -XX:MaxTenuringThreshold=1 -XX:SurvivorRatio=32 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true -jar paper-26.2-121.jar --nogui

pause
