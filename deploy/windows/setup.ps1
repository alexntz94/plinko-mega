# Plinko Mega demo on Windows: makes .env and secrets\ (strong random values) and saves the Cloudflare tunnel token.
# Run once, from windows-setup.cmd. It never overwrites anything that is already there, so it is safe to run again
# (for example only to replace the tunnel token: delete secrets\CLOUDFLARE_TUNNEL_TOKEN first).
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')

$utf8 = New-Object System.Text.UTF8Encoding $false   # no BOM, no newline: Docker reads the files as they are
function Random-Text([int]$length) {
    $bytes = New-Object byte[] 96
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    ([Convert]::ToBase64String($bytes) -replace '[/+=]', '').Substring(0, $length)
}
function Write-Secret([string]$name, [string]$value) {
    $path = Join-Path 'secrets' $name
    if (Test-Path $path) { Write-Host "  kept    secrets\$name"; return }
    [System.IO.File]::WriteAllText((Join-Path (Get-Location) $path), $value, $utf8)
    Write-Host "  made    secrets\$name"
}

Write-Host 'Secrets:'
New-Item -ItemType Directory -Force secrets | Out-Null
Write-Secret MYSQL_ROOT_PASSWORD (Random-Text 40)
Write-Secret DB_OWNER_PASSWORD (Random-Text 40)
Write-Secret DB_PASSWORD (Random-Text 40)
Write-Secret ADMIN_PASSWORD ''          # empty: first admin sign-in is admin / admin, then 2FA and a new password
Write-Secret ROUND_SIGNING_KEY (Random-Text 64)
Write-Secret ROUND_KEYS ('r1=' + (Random-Text 64))
Write-Secret RECORD_KEYS ('d1=' + (Random-Text 64))
Write-Secret ENCRYPTION_KEYS ('e1=' + (Random-Text 64))
Write-Secret PAM_API_KEY (Random-Text 32)
Write-Secret PAM_SECRET (Random-Text 64)

if (-not (Test-Path 'secrets\CLOUDFLARE_TUNNEL_TOKEN')) {
    Write-Host ''
    Write-Host 'Paste the token of the Cloudflare tunnel "plinko-mega" (it starts with eyJ) and press Enter.'
    Write-Host 'It is not shown on screen.'
    $secure = Read-Host -AsSecureString 'Token'
    $token = [System.Net.NetworkCredential]::new('', $secure).Password.Trim()
    if (-not $token.StartsWith('eyJ')) { throw 'That does not look like a tunnel token (it should start with eyJ). Run setup again.' }
    [System.IO.File]::WriteAllText((Join-Path (Get-Location) 'secrets\CLOUDFLARE_TUNNEL_TOKEN'), $token, $utf8)
    Write-Host '  made    secrets\CLOUDFLARE_TUNNEL_TOKEN'
} else {
    Write-Host '  kept    secrets\CLOUDFLARE_TUNNEL_TOKEN'
}

if (-not (Test-Path '.env')) {
    $envText = @'
ADMIN_USERNAME=admin
# The demo uses the built-in mock wallet (fake credits), never a real one
MOCK_PAM_ENABLED=true
PAM_BASE_URL=http://localhost:8080/mock-pam
PAM_ALLOW_HTTP=false
REQUIRE_SECOND_APPROVER=true
# the admin panel is opened on this machine over plain http://127.0.0.1:8091
COOKIE_SECURE=false
# the public address (link previews use it)
GAME_URL=https://mega.plinko-game.dev/
'@
    [System.IO.File]::WriteAllText((Join-Path (Get-Location) '.env'), $envText, $utf8)
    Write-Host '  made    .env'
} else {
    Write-Host '  kept    .env'
}
Write-Host ''
Write-Host 'Done. Next: double-click windows-start.cmd'
