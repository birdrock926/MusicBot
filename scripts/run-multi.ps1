<#
  Simple multi-instance launcher for JMusicBot (Windows PowerShell).
  Usage:
    ./scripts/run-multi.ps1 configA.txt configB.txt
  Optional:
    -Jar "target/JMusicBot.jar"    # custom jar path
    Set environment variables for Redis if使う:
      $env:JMUSICBOT_REDIS_URI="redis://localhost:6379"
      $env:JMUSICBOT_INSTANCE="..." # overrides instance.id if set
#>
param(
    [string[]] $Configs,
    [string]   $Jar = "target\JMusicBot.jar"
)

if(-not $Configs -or $Configs.Count -eq 0) {
    Write-Host "Usage: ./scripts/run-multi.ps1 configA.txt configB.txt [-Jar path\to\JMusicBot.jar]" -ForegroundColor Yellow
    exit 1
}

if(-not (Test-Path $Jar)) {
    # try to find the freshest jar in target
    $candidate = Get-ChildItem -Path "target" -Filter "JMusicBot*.jar" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if($candidate) {
        $Jar = $candidate.FullName
    } else {
        Write-Host "Jar not found. Build first (mvn package) or specify -Jar." -ForegroundColor Red
        exit 1
    }
}

foreach($cfg in $Configs) {
    if(-not (Test-Path $cfg)) {
        Write-Host "Config not found: $cfg" -ForegroundColor Red
        continue
    }
    $resolvedCfg = (Resolve-Path $cfg).Path
    $base = [IO.Path]::GetFileNameWithoutExtension($resolvedCfg)
    $instanceId = $env:JMUSICBOT_INSTANCE
    if([string]::IsNullOrWhiteSpace($instanceId)) {
        $instanceId = $base
    }

    $args = @("-Dinstance.id=$instanceId", "-Dconfig.file=$resolvedCfg", "-jar", $Jar)
    Write-Host "Starting instance '$instanceId' with $resolvedCfg" -ForegroundColor Cyan
    Start-Process -FilePath "java" -ArgumentList $args -NoNewWindow
}

Write-Host "Launched $($Configs.Count) instance(s)." -ForegroundColor Green


