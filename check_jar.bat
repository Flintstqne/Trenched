@echo off
echo === Checking Trenched-1.jar for IF classes ===
jar tf "C:\Users\brayd\IdeaProjects\Minecraft\Entrenched\target\Trenched-1.jar" > "C:\Users\brayd\IdeaProjects\Minecraft\Entrenched\jarlist.txt" 2>&1
findstr /i "inventoryframework" "C:\Users\brayd\IdeaProjects\Minecraft\Entrenched\jarlist.txt"
echo.
echo === Checking for Pane.class specifically ===
findstr /i "Pane.class" "C:\Users\brayd\IdeaProjects\Minecraft\Entrenched\jarlist.txt"
echo.
echo === Checking IF in Maven cache ===
dir "C:\Users\brayd\.m2\repository\com\github\stefvanschie\inventoryframework\IF\0.11.6\" 2>&1
echo.
echo === DONE ===

