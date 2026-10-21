# Stops the Plinko Mega demo. Its data (demo wallets, rounds, admin accounts) is kept; the other Plinko is not touched.
Set-Location (Join-Path $PSScriptRoot '..\..')
docker compose -f docker-compose.yml -f docker-compose.demo.yml down
