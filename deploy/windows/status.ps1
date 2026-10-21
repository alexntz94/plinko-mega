# Shows the Plinko Mega containers and the tunnel's last messages.
Set-Location (Join-Path $PSScriptRoot '..\..')
docker compose -f docker-compose.yml -f docker-compose.demo.yml ps
Write-Host ''
Write-Host 'Tunnel (last lines):'
docker compose -f docker-compose.yml -f docker-compose.demo.yml logs --tail 8 cloudflared
