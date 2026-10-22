# Moves the demo's data from another machine to this one: the database (players, wallets, rounds, admin accounts,
# settings) and the secrets it is signed and encrypted with. Run from windows-import.cmd, with the copy unzipped in
# migration\ (db-data.tar.gz and secrets\). This machine's own Plinko Mega data is replaced; the other Plinko is not
# touched. windows-start.cmd runs it by itself (-FromStart, no question) when it finds migration\db-data.tar.gz; once
# loaded, the folder is renamed migration-imported so it happens only once.
param([switch]$FromStart)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')
$root = (Get-Location).Path
if (-not (Test-Path 'migration\db-data.tar.gz')) {
    throw 'migration\db-data.tar.gz is missing: unzip the full copy into this folder first.'
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running: start Docker Desktop and try again.' }

if (-not $FromStart) {
    Write-Host 'This replaces the Plinko Mega data on this machine with the copy in migration\.'
    $answer = Read-Host 'Type YES to go on'
    if ($answer -ne 'YES') { Write-Host 'Nothing changed.'; exit 0 }
}

Write-Host 'Stopping Plinko Mega...'
docker compose -f docker-compose.yml -f docker-compose.demo.yml down
if ($LASTEXITCODE -ne 0) { throw 'Stopping failed: see the messages above.' }

# the secrets: the database only opens with the passwords and keys it was made with. A tunnel token already on this
# machine is kept.
New-Item -ItemType Directory -Force secrets | Out-Null
foreach ($file in @(Get-ChildItem 'migration\secrets' -File -ErrorAction SilentlyContinue)) {
    if ($file.Name -eq 'CLOUDFLARE_TUNNEL_TOKEN' -and (Test-Path 'secrets\CLOUDFLARE_TUNNEL_TOKEN')) {
        Write-Host '  kept    secrets\CLOUDFLARE_TUNNEL_TOKEN'
        continue
    }
    Copy-Item $file.FullName (Join-Path 'secrets' $file.Name) -Force
    Write-Host "  copied  secrets\$($file.Name)"
}

Write-Host 'Loading the database...'
# through cmd: on a new machine there is no volume yet, and Windows PowerShell stops on docker's "no such volume"
cmd /c 'docker volume rm plinko-mega_db-data >nul 2>&1'
docker volume create plinko-mega_db-data | Out-Null
docker run --rm -v plinko-mega_db-data:/var/lib/mysql -v "${root}\migration:/m:ro" alpine:3.20 `
    sh -c 'tar -xzpf /m/db-data.tar.gz -C /var/lib/mysql && chown -R 999:999 /var/lib/mysql'
if ($LASTEXITCODE -ne 0) { throw 'Loading the database failed: see the messages above.' }
if (Test-Path 'migration-imported') { Remove-Item 'migration-imported' -Recurse -Force }
Rename-Item 'migration' 'migration-imported'

# .env if this machine has none yet (the copied secrets are kept as they are)
& (Join-Path $PSScriptRoot 'setup.ps1')

Write-Host ''
Write-Host 'Data moved.'
if (-not $FromStart) { & (Join-Path $PSScriptRoot 'start.ps1') }
