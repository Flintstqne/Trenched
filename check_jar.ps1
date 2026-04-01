Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = "C:\Users\brayd\IdeaProjects\Minecraft\Entrenched\target\Trenched-1.jar"
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
$all = $zip.Entries | Where-Object { $_.FullName -like "*inventoryframework*" -or $_.FullName -like "*stefvanschie*" }
"=== IF/InventoryFramework entries in JAR ==="
"Count: $($all.Count)"
$all | Select-Object -First 20 -ExpandProperty FullName
"=== Checking for Pane class specifically ==="
$pane = $zip.Entries | Where-Object { $_.FullName -like "*Pane*" -and $_.FullName -like "*.class" }
$pane | Select-Object -First 10 -ExpandProperty FullName
$zip.Dispose()
"=== DONE ==="

