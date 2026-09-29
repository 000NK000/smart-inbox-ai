$java17Home = "C:\Program Files\Microsoft\jdk-17.0.17.10-hotspot"

# Set JAVA_HOME
echo "Setting JAVA_HOME to $java17Home"
[Environment]::SetEnvironmentVariable("JAVA_HOME", $java17Home, "User")

# Prepend %JAVA_HOME%\bin to Path
$currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
if (-not $currentPath.Contains($java17Home)) {
    echo "Adding Java 17 bin to User Path"
    $newPath = "$java17Home\bin;$currentPath"
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
} else {
    echo "Java 17 bin is already in Path, attempting to move it to the front..."
    # Simple prepend to ensure it is first, cleaning up duplicates is harder but this usually works for precedence
    $newPath = "$java17Home\bin;" + $currentPath.Replace("$java17Home\bin;", "")
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
}

echo "Environment variables updated. You MUST restart your terminal for changes to take effect."
