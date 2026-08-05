# Running TriageMate on a custom domain (instead of `localhost:8080`)

**Goal:** the browser address bar reads `http://triagemate.auspost.com.au` rather than
`http://localhost:8080`, so the demo looks like a deployed internal service instead of
someone's laptop.

**What this actually is:** a local hostname alias plus a port change. Nothing is
published, no DNS is registered, no traffic leaves the machine — the name resolves only
on your laptop, via your own `hosts` file. It is cosmetic-but-convincing, which is
exactly the intent. Say so if anyone asks: claiming it's a real deployment would be a
different thing entirely.

## Quickest path: use the script

```bash
sudo ./bin/setup-custom-domain.sh          # macOS / Linux
./bin/setup-custom-domain.sh               # Windows: Git Bash "Run as administrator"
```

Idempotent, backs the file up first, and verifies the name resolves. **On Linux it
also lowers the unprivileged-port floor** (`net.ipv4.ip_unprivileged_port_start=80`,
persisted in `/etc/sysctl.d/`) so `./run-*.sh` can bind port 80 as your normal user —
sudo is needed to set up, never to run.
`--check` reports status without changing anything; `--remove` undoes it (and only
ever deletes the line the script itself added). The rest of this document is the
manual equivalent, plus the port and no-admin details the script points at.

---

Time: ~5 minutes. Needs local admin rights (editing `hosts`) — see
[If you can't get admin rights](#if-you-cant-get-admin-rights) for a fallback that needs
none.

---

## Step 1 — Point the name at your own machine

Add one line to the `hosts` file. Pick whichever name you prefer; `.local` and
`.internal` are safe because they can't collide with a real public domain.

### Windows (corporate laptop)

1. Press **Start**, type `Notepad`, right-click it → **Run as administrator**.
   (Editing `hosts` without elevation fails silently with "Access denied" on save —
   this is the step people usually miss.)
2. **File → Open** → paste this path and press Enter:
   ```
   C:\Windows\System32\drivers\etc\hosts
   ```
   You'll need to switch the file-type dropdown from *Text Documents* to **All Files**
   to see it.
3. Add this line at the end:
   ```
   127.0.0.1    triagemate.auspost.com.au
   ```
4. Save and close.

### macOS / Linux

```bash
echo "127.0.0.1    triagemate.auspost.com.au" | sudo tee -a /etc/hosts
```

### Verify the name resolves

```bash
ping triagemate.auspost.com.au
```
You want replies from `127.0.0.1`. If it doesn't resolve, the `hosts` edit didn't save
(almost always the admin-rights problem above).

### Changing the name later

The hostname was `triagemate.auspost.local` until **2026-08-05**. To rename it again,
edit `DEFAULT_HOST` in `bin/setup-custom-domain.sh` (or pass the new name as an
argument) and re-run:

```bash
sudo ./bin/setup-custom-domain.sh                      # uses the new DEFAULT_HOST
sudo ./bin/setup-custom-domain.sh other.example.com    # or a one-off name
```

The script retires any name it previously added in the same pass, so you end up with
**one** mapping rather than two and the old name stops resolving. Entries added by hand
(or by your IT team) are deliberately left alone — `--check` will show them if the old
name still answers after a re-run.

Also update `triage.ui.public-hostname` in `src/main/resources/application.yml`, which is
what the startup banner prints. It is display-only; the app never binds to it.

> **One caveat with `.com.au`.** `auspost.com.au` is a real public domain, so this hosts
> entry shadows whatever public DNS says for that exact name, and a corporate proxy or PAC
> file may route `*.auspost.com.au` through the proxy rather than honouring `hosts` at all.
> If `curl` reaches it but the browser doesn't, that's the proxy — check the PAC file or add
> a proxy bypass for the name. The previous `.local` name had neither problem (it had a
> different one: `.local` is mDNS territory, which is why the startup banner's lookup is
> time-bounded — see J20/STV-6).

---

## Step 2 — Serve on port 80 so the URL needs no `:8080`

`http://triagemate.auspost.com.au` with no port means port 80. Two options.

### Option A — just run on port 80 (simplest)

```bash
./run-deterministic.sh --server.port=80
```
or for the live agent mode:
```bash
./run-adk.sh --server.port=80
```

Both scripts forward extra arguments to the application (they were changed to do this —
previously they went to Maven, which rejected them), and the startup banner prints the
port you actually chose.

- **On Windows** this generally works without elevation.
- **On macOS/Linux** ports below 1024 need root. Either run with `sudo`, or use Option B.

If port 80 is already taken (IIS, Docker Desktop, another dev server), you'll get
`Port 80 was already in use` — use Option B instead, or stop the other service.

### Option B — keep port 8080, accept the port in the URL

Change nothing about how you run it. The URL becomes:

```
http://triagemate.auspost.com.au:8080
```

Less clean, but it still reads as a hostname rather than `localhost`, needs no admin
rights for the port, and can't collide with anything. **This is the safer choice if
you're unsure** — the hostname is doing most of the work visually.

---

## Step 3 — Run it and open the new URL

```bash
./run-deterministic.sh --server.port=80
```

Then open **http://triagemate.auspost.com.au** (or with `:8080` for Option B).

---

## Making it permanent (optional)

If you'd rather not pass `--server.port` every time, set the port in
`src/main/resources/application.yml`:

```yaml
server:
  port: 80
```

Prefer the command-line flag for the demo: it leaves the committed default at 8080, so
the repo still behaves normally for everyone else and for the test suite.

---

## If you can't get admin rights

You can't edit `hosts` without them, so the hostname isn't available. Fallbacks, best
first:

1. **`localtest.me`** — a public DNS name that resolves to `127.0.0.1` for anyone,
   including all subdomains, with no local configuration at all. Just open
   `http://triagemate.localtest.me:8080`. Requires working DNS (it's a real public
   lookup), so it won't work fully offline.
2. **Raise a service-desk ticket** for the one-line `hosts` addition — it's a common,
   low-risk request.
3. **Accept `localhost`.** It is the least interesting option but nothing breaks.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Name doesn't resolve | `hosts` edit didn't save | Re-open Notepad **as administrator** |
| Still resolves after removing the line | DNS cache | Windows: `ipconfig /flushdns` · macOS: `sudo dscacheutil -flushcache` |
| `Port 80 was already in use` | IIS / Docker / another server | Use Option B, or stop the other service |
| `Permission denied` binding port 80 | macOS/Linux, port <1024 | `sudo`, or use Option B |
| Browser forces `https://` and fails | HSTS or a search-domain rewrite | Type the `http://` prefix explicitly |
| Corporate proxy intercepts the name | Proxy doesn't know it's local | Add the host to the proxy bypass list, or use Option B |

---

## Reverting

```bash
sudo ./bin/setup-custom-domain.sh --remove
```

Or delete the line by hand. Either way, drop the `--server.port` flag too. Nothing
else was changed.
