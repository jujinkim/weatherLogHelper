param()

$ErrorActionPreference = "Stop"

function Write-Json($obj) {
  $json = $obj | ConvertTo-Json -Compress
  Write-Output $json
}

function Write-ErrorJson($message) {
  Write-Json @{ status = "error"; message = $message }
}

function Resolve-DefaultHome {
  if ($env:USERPROFILE) { return "$env:USERPROFILE\.wlh" }
  return "$env:HOMEDRIVE$env:HOMEPATH\.wlh"
}

function Acquire-Lock($path) {
  $attempts = 0
  while ($attempts -lt 50) {
    if (-not (Test-Path $path)) {
      New-Item -ItemType Directory -Path $path | Out-Null
      return $true
    }
    Start-Sleep -Milliseconds 100
    $attempts++
  }
  return $false
}

function Release-Lock($path) {
  if (Test-Path $path) { Remove-Item -Recurse -Force $path }
}

function Resolve-Java {
  if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    return "$env:JAVA_HOME\bin\java.exe"
  }
  return "java"
}

function Ensure-Dirs($home) {
  New-Item -ItemType Directory -Force -Path "$home\engine" | Out-Null
  New-Item -ItemType Directory -Force -Path "$home\daemon" | Out-Null
  New-Item -ItemType Directory -Force -Path "$home\cache" | Out-Null
  New-Item -ItemType Directory -Force -Path "$home\config" | Out-Null
  New-Item -ItemType Directory -Force -Path "$home\logs" | Out-Null
}

function Write-StartingFile($home) {
  $path = "$home\daemon\daemon.starting.json"
  $obj = @{ pid = $PID; startedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") }
  $obj | ConvertTo-Json -Compress | Set-Content $path
}

function Remove-StartingFile($home) {
  $path = "$home\daemon\daemon.starting.json"
  if (Test-Path $path) { Remove-Item -Force $path }
}

function Read-ConfigBaseUrl($home) {
  $configPath = "$home\config\wlh.json"
  if (-not (Test-Path $configPath)) { return "" }
  try {
    $obj = Get-Content $configPath -Raw | ConvertFrom-Json
    return $obj.updateBaseUrl
  } catch {
    return ""
  }
}

function Ensure-Engine($home, $baseUrl, $noUpdate) {
  $engineJar = "$home\engine\wlh-engine.jar"
  if ($noUpdate -and (Test-Path $engineJar)) { return }
  if (-not $baseUrl) {
    if (Test-Path $engineJar) {
      return
    }
    Write-ErrorJson "base_url_missing"
    exit 1
  }

  $lockDir = "$home\engine\download.lock"
  if (-not (Acquire-Lock $lockDir)) {
    Write-ErrorJson "download_lock_busy"
    exit 1
  }
  try {
    $latestUrl = "$baseUrl/latest.json"
    $latestJson = Invoke-WebRequest -UseBasicParsing -Uri $latestUrl | Select-Object -ExpandProperty Content
    $latest = $latestJson | ConvertFrom-Json
    if (-not $latest.version -or -not $latest.artifact -or -not $latest.sha256) {
      Write-ErrorJson "invalid_latest"
      exit 1
    }

    $tempFile = [System.IO.Path]::GetTempFileName()
    Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/$($latest.artifact)" -OutFile $tempFile
    $hash = (Get-FileHash -Algorithm SHA256 -Path $tempFile).Hash.ToLower()
    if ($hash -ne $latest.sha256.ToLower()) {
      Remove-Item $tempFile -Force
      if (Test-Path $engineJar) { return }
      Write-ErrorJson "sha_mismatch"
      exit 1
    }
    Move-Item -Force $tempFile $engineJar
    $installed = @{ version = $latest.version; installedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") }
    $installed | ConvertTo-Json -Compress | Set-Content "$home\engine\installed.json"
  } catch {
    if (Test-Path $engineJar) { return }
    Write-ErrorJson "update_failed"
    exit 1
  } finally {
    Release-Lock $lockDir
  }
}

function Read-DaemonInfo($home) {
  $daemonPath = "$home\daemon\daemon.json"
  if (-not (Test-Path $daemonPath)) { return $null }
  try {
    return (Get-Content $daemonPath -Raw | ConvertFrom-Json)
  } catch {
    return $null
  }
}

function Is-DaemonAlive($home) {
  $info = Read-DaemonInfo $home
  if (-not $info) { return $false }
  try {
    Get-Process -Id $info.pid | Out-Null
  } catch {
    Remove-Item "$home\daemon\daemon.json" -Force -ErrorAction SilentlyContinue
    return $false
  }
  try {
    Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$($info.port)/api/v1/status" | Out-Null
    return $true
  } catch {
    Remove-Item "$home\daemon\daemon.json" -Force -ErrorAction SilentlyContinue
    return $false
  }
}

function Start-Daemon($home, $baseUrl, $noUpdate) {
  if (Is-DaemonAlive $home) {
    Write-Json @{ status = "ok"; started = $false }
    return
  }
  $lockDir = "$home\daemon\daemon.lock"
  if (-not (Acquire-Lock $lockDir)) {
    $attempts = 0
    while ($attempts -lt 50) {
      if (Is-DaemonAlive $home) {
        Write-Json @{ status = "ok"; started = $false }
        return
      }
      Start-Sleep -Milliseconds 200
      $attempts++
    }
    Write-ErrorJson "daemon_lock_busy"
    exit 1
  }
  try {
    if (Is-DaemonAlive $home) {
      Write-Json @{ status = "ok"; started = $false }
      return
    }
    Write-StartingFile $home
    Ensure-Engine $home $baseUrl $noUpdate
    $java = Resolve-Java
    $logFile = "$home\logs\daemon.log"
    Start-Process -FilePath $java -ArgumentList "-jar", "$home\engine\wlh-engine.jar", "--home", $home, "--port", "0" -RedirectStandardOutput $logFile -RedirectStandardError $logFile -WindowStyle Hidden | Out-Null
    $attempts = 0
    while ($attempts -lt 50) {
      if (Is-DaemonAlive $home) {
        Write-Json @{ status = "ok"; started = $true }
        return
      }
      Start-Sleep -Milliseconds 200
      $attempts++
    }
    Write-ErrorJson "daemon_start_failed"
    exit 1
  } finally {
    Remove-StartingFile $home
    Release-Lock $lockDir
  }
}

function Stop-Daemon($home) {
  if (-not (Is-DaemonAlive $home)) {
    Write-Json @{ status = "ok"; stopped = $false }
    return
  }
  $info = Read-DaemonInfo $home
  Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$($info.port)/api/v1/shutdown" | Out-Null
  Write-Json @{ status = "ok"; stopped = $true }
}

function Proxy-Status($home) {
  if (-not (Is-DaemonAlive $home)) {
    Write-Json @{ status = "not_running" }
    return
  }
  $info = Read-DaemonInfo $home
  $resp = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$($info.port)/api/v1/status"
  Write-Output ($resp | ConvertTo-Json -Compress)
}

function Proxy-Scan($home, $file, $mode) {
  if (-not (Is-DaemonAlive $home)) { Start-Daemon $home $BaseUrl $NoUpdate | Out-Null }
  $info = Read-DaemonInfo $home
  $payload = @{ file = $file; mode = $mode } | ConvertTo-Json -Compress
  $resp = Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$($info.port)/api/v1/scan" -ContentType "application/json" -Body $payload
  Write-Output ($resp | ConvertTo-Json -Compress)
}

function Wait-Job($home, $jobId) {
  $info = Read-DaemonInfo $home
  $attempts = 0
  while ($attempts -lt 240) {
    $resp = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$($info.port)/api/v1/job/$jobId"
    if ($resp.status -eq "completed") { return $true }
    if ($resp.status -eq "failed") { return $false }
    Start-Sleep -Milliseconds 500
    $attempts++
  }
  return $false
}

function Proxy-Result($home, $endpoint, $query) {
  $info = Read-DaemonInfo $home
  $resp = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$($info.port)/api/v1/result/$endpoint$query"
  Write-Output ($resp | ConvertTo-Json -Compress)
}

function Proxy-Decrypt($home, $file, $jar, $timeout) {
  if (-not (Is-DaemonAlive $home)) { Start-Daemon $home $BaseUrl $NoUpdate | Out-Null }
  $info = Read-DaemonInfo $home
  $payload = @{ file = $file; jar = $jar; timeoutSeconds = [int]$timeout } | ConvertTo-Json -Compress
  $resp = Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$($info.port)/api/v1/decrypt" -ContentType "application/json" -Body $payload
  Write-Output ($resp | ConvertTo-Json -Compress)
}

function Proxy-AdbDevices($home, $adb) {
  if (-not (Is-DaemonAlive $home)) { Start-Daemon $home $BaseUrl $NoUpdate | Out-Null }
  $info = Read-DaemonInfo $home
  $query = ""
  if ($adb) {
    $query = "?adb=$([System.Web.HttpUtility]::UrlEncode($adb))"
  }
  $resp = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$($info.port)/api/v1/adb/devices$query"
  Write-Output ($resp | ConvertTo-Json -Compress)
}

function Proxy-AdbRun($home, $serial, $adb, $adbArgs) {
  if (-not (Is-DaemonAlive $home)) { Start-Daemon $home $BaseUrl $NoUpdate | Out-Null }
  $info = Read-DaemonInfo $home
  $payload = @{ serial = $serial; args = $adbArgs }
  if ($adb) { $payload.adb = $adb }
  $body = $payload | ConvertTo-Json -Compress
  $resp = Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$($info.port)/api/v1/adb/run" -ContentType "application/json" -Body $body
  Write-Output ($resp | ConvertTo-Json -Compress)
}

$ArgsList = New-Object System.Collections.Generic.List[string]
$ArgsList.AddRange($args)
$NoUpdate = $false
$Home = ""
$BaseUrl = ""

while ($ArgsList.Count -gt 0) {
  switch ($ArgsList[0]) {
    "--home" { $Home = $ArgsList[1]; $ArgsList.RemoveRange(0,2) }
    "--base-url" { $BaseUrl = $ArgsList[1]; $ArgsList.RemoveRange(0,2) }
    "--no-update" { $NoUpdate = $true; $ArgsList.RemoveAt(0) }
    default { break }
  }
}

if (-not $Home) { $Home = Resolve-DefaultHome }
Ensure-Dirs $Home
if (-not $BaseUrl) { $BaseUrl = Read-ConfigBaseUrl $Home }

$Command = if ($ArgsList.Count -gt 0) { $ArgsList[0] } else { "" }
$ArgsList = if ($ArgsList.Count -gt 1) { $ArgsList.GetRange(1, $ArgsList.Count - 1) } else { @() }

switch ($Command) {
  "status" { if (-not $NoUpdate) { Ensure-Engine $Home $BaseUrl $NoUpdate }; Proxy-Status $Home }
  "start" { Start-Daemon $Home $BaseUrl $NoUpdate }
  "stop" { Stop-Daemon $Home }
  "restart" { Stop-Daemon $Home | Out-Null; Start-Daemon $Home $BaseUrl $NoUpdate }
  "update" { Ensure-Engine $Home $BaseUrl $NoUpdate; Write-Json @{ status = "ok" } }
  "scan" {
    $file = ""
    for ($i=0; $i -lt $ArgsList.Count; $i++) {
      $file = $ArgsList[$i]
    }
    if (-not $file) { Write-ErrorJson "missing_file"; exit 1 }
    Proxy-Scan $Home $file "full"
  }
  "versions" {
    $file = $ArgsList[0]
    if (-not $file) { Write-ErrorJson "missing_file"; exit 1 }
    $scan = Proxy-Scan $Home $file "full" | ConvertFrom-Json
    if (-not (Wait-Job $Home $scan.jobId)) { Write-ErrorJson "scan_failed"; exit 1 }
    Proxy-Result $Home "versions" ""
  }
  "crashes" {
    $file = $ArgsList[0]
    if (-not $file) { Write-ErrorJson "missing_file"; exit 1 }
    $scan = Proxy-Scan $Home $file "full" | ConvertFrom-Json
    if (-not (Wait-Job $Home $scan.jobId)) { Write-ErrorJson "scan_failed"; exit 1 }
    Proxy-Result $Home "crashes" ""
  }
  "decrypt" {
    $file = ""; $jar = ""; $timeout = 30
    for ($i=0; $i -lt $ArgsList.Count; $i++) {
      switch ($ArgsList[$i]) {
        "--jar" { $jar = $ArgsList[$i+1]; $i++ }
        "--timeout" { $timeout = $ArgsList[$i+1]; $i++ }
        default { $file = $ArgsList[$i] }
      }
    }
    if (-not $file -or -not $jar) { Write-ErrorJson "missing_fields"; exit 1 }
    Proxy-Decrypt $Home $file $jar $timeout
  }
  "adb" {
    $sub = if ($ArgsList.Count -gt 0) { $ArgsList[0] } else { "" }
    if ($sub -eq "devices") {
      $adb = ""
      if ($ArgsList.Count -ge 3 -and $ArgsList[1] -eq "--adb") { $adb = $ArgsList[2] }
      Proxy-AdbDevices $Home $adb
    } elseif ($sub -eq "run") {
      $serial = ""; $adb = ""; $argsIndex = 0
      for ($i=1; $i -lt $ArgsList.Count; $i++) {
        switch ($ArgsList[$i]) {
          "--serial" { $serial = $ArgsList[$i+1]; $i++ }
          "--adb" { $adb = $ArgsList[$i+1]; $i++ }
          "--" { $argsIndex = $i + 1; break }
        }
      }
      if (-not $serial) { Write-ErrorJson "missing_serial"; exit 1 }
      $adbArgs = @()
      if ($argsIndex -gt 0 -and $argsIndex -lt $ArgsList.Count) {
        $adbArgs = $ArgsList[$argsIndex..($ArgsList.Count-1)]
      }
      if ($adbArgs.Count -eq 0) { Write-ErrorJson "missing_args"; exit 1 }
      Proxy-AdbRun $Home $serial $adb $adbArgs
    } else {
      Write-ErrorJson "unknown_adb_command"
    }
  }
  default { Write-ErrorJson "unknown_command" }
}
