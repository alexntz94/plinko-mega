# Builds and starts the Plinko Mega demo (Compose project "plinko-mega": the other Plinko is not touched), then
# shows whether the Cloudflare tunnel is connected.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')
# a data copy from another machine, not loaded yet: load it first (once)
if (Test-Path 'migration\db-data.tar.gz') { & (Join-Path $PSScriptRoot 'import.ps1') -FromStart }
if (-not (Test-Path 'secrets\CLOUDFLARE_TUNNEL_TOKEN') -or -not (Test-Path '.env')) {
    throw 'Run windows-setup.cmd first.'
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running: start Docker Desktop and try again.' }

Write-Host 'Building and starting (the first time takes several minutes)...'
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Starting failed: see the messages above.' }

Write-Host ''
docker compose -f docker-compose.yml -f docker-compose.demo.yml ps
Write-Host ''
Write-Host 'Waiting for the tunnel...'
for ($i = 0; $i -lt 20; $i++) {
    $log = cmd /c 'docker compose -f docker-compose.yml -f docker-compose.demo.yml logs cloudflared 2>&1' | Out-String   # through cmd: Windows PowerShell stops on anything docker writes to stderr
    if ($log -match 'Registered tunnel connection') { break }
    Start-Sleep -Seconds 3
}
if ($log -match 'Registered tunnel connection') {
    Write-Host 'Tunnel connected. The demo is at https://mega.plinko-game.dev/demo'
} else {
    Write-Host 'The tunnel has not connected yet. Run windows-status.cmd in a minute, or check the token.'
}
Write-Host 'Admin panel on this machine: http://127.0.0.1:8091/admin (first time: admin / admin)'
