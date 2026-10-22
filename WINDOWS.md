# Plinko Mega demo on the Windows server

The demo runs next to the other Plinko on the same machine without touching it:

| | Plinko (already there) | Plinko Mega (this folder) |
|---|---|---|
| Public address | https://plinko-game.dev/demo | https://mega.plinko-game.dev/demo |
| Cloudflare tunnel | plinko-demo | plinko-mega |
| Docker project | its own | `plinko-mega` |
| Game, on the server | 127.0.0.1:8080 | 127.0.0.1:8090 |
| Admin panel, on the server | http://127.0.0.1:8081/admin | http://127.0.0.1:8091/admin |

Nothing is opened to the internet: visitors reach the game only through the Cloudflare tunnel.

## 1. Create the tunnel (Cloudflare dashboard, 2 minutes)

1. In Cloudflare, go to **Zero Trust → Networks → Tunnels → Create a tunnel**. Choose **Cloudflared** and name it `plinko-mega`.
2. On the next page Cloudflare shows install commands. Don't run them. Copy only the **token**: the long text after `--token` (it starts with `eyJ`). Keep it for step 3.
3. Click **Next**. Add a **public hostname**:
   - Subdomain: `mega`
   - Domain: `plinko-game.dev`
   - Service type: **HTTP**
   - URL: `web:8080`

   Save. Cloudflare creates the `mega.plinko-game.dev` address by itself.

`web:8080` is the game's own container inside this project. The other Plinko's tunnel uses the same words, but each tunnel only reaches its own project.

## 2. Put the folder on the server

Unzip `plinko-mega.zip` anywhere, for example `C:\plinko-mega`. Use a different folder from the other Plinko.

Docker Desktop must be running. It already is for the other Plinko.

## 3. Setup (once)

Double-click **windows-setup.cmd**. It does three things:

- It makes the settings (`.env`) and strong random secrets (`secrets\`).
- It asks for the tunnel token from step 1. Paste it and press Enter; it isn't shown on screen.
- If you run it again later, it never overwrites anything.

## 4. Start

Double-click **windows-start.cmd**. The first build takes several minutes. When it says **Tunnel connected**, open:

**https://mega.plinko-game.dev/demo**

## Every day

| What | How |
|---|---|
| Is it running? | **windows-status.cmd** |
| Stop it (data is kept) | **windows-stop.cmd** |
| Start again | **windows-start.cmd** |
| Update to a new version | Unzip the new zip over this folder (`.env` and `secrets\` stay), then **windows-start.cmd** |

The containers restart by themselves after a reboot, as long as Docker Desktop starts with Windows (Docker Desktop → Settings → General → *Start Docker Desktop when you sign in*).

## Moving the data from another machine

To keep the players, wallets, rounds, admin accounts and settings of a demo that ran elsewhere:

1. Unzip the full copy (`plinko-mega-full.zip`) into this folder. It adds a `migration` folder with the database and its secrets. **It holds passwords: carry it on a USB stick, not by email or chat, and delete it afterwards.**
2. Double-click **windows-import.cmd** and type `YES`. It stops Plinko Mega, replaces its data with the copy, keeps this machine's tunnel token, and starts it again.
3. Delete the `migration` folder and the zip.

Stop the demo on the other machine once this one says **Tunnel connected**: two machines on the same tunnel share the visitors between them.

## Admin panel

On the server, open **http://127.0.0.1:8091/admin**. The first time, sign in with **admin / admin** within 24 hours; it then asks you to set up 2FA and choose a new password.

## If something goes wrong

- **The tunnel does not connect:** the token is probably wrong. Delete `secrets\CLOUDFLARE_TUNNEL_TOKEN`, run **windows-setup.cmd** again, paste the token, then run **windows-start.cmd**.
- **"Docker is not running":** start Docker Desktop, wait until it says *running*, and try again.
- **The site shows an error 502:** in the tunnel's public hostname, check that the URL is `web:8080` with type HTTP.
