# snapraid_aio.bb

 **All-in-one SnapRAID management script.**  

A [Babashka](https://github.com/babashka/babashka)-powered tool that handles preflight checks, drive health monitoring, logging, and SnapRAID operations — in one script.

---

## ✨ Features

- **All-in-One Workflow**
    - Run health checks, preflight safety tests, and SnapRAID commands in a single entrypoint.
- **SnapRAID Integration**
    - Supports `diff`, `sync`, and `scrub`.
    - Uses SnapRAID configuration
- **Safety First**
    - Preflight checks for:
        - Unmounted data and parity drives
        - Missing dependencies
        - Script already running
        - SnapRAID already running
- **Drive Health Monitoring**
    - Runs S.M.A.R.T. checks before running SnapRAID operations.
- **Logging**
    - Console + logfile with timestamps.
- **Configurable**
    - Default SnapRAID configuration, overridable with `--config`.
    - Set the percent of blocks SnapRAID will scrub with `--scrub-percent`.
---

## 🚀 Usage

```bash
snapraid_aio.bb [options]
```

### Options

| Flag                    | Description                                        |
|-------------------------|----------------------------------------------------|
| `-c`, `--config`        | Path to SnapRAID configuration file.               |
| `-h`, `--help`          | Display helpful information.                       |
| `-p`, `--scrub-percent` | Percentage of blocks to scrub (0-100). Default 10. |
| `-v`, `--version`       | Display the version.                               |

Examples:

```bash
snapraid_aio.bb --config /etc/snapraid.conf --scrub-percent 20
snapraid_aio.bb --help
snapraid_aio.bb --version
```

---

## 📦 Installation

### Requirements
- Linux (tested on Linux Mint)
- [Babashka](https://github.com/babashka/babashka) (v1.3+ recommended)
- SnapRAID installed somewhere on the `$PATH`
- `util-linux` package (for `findmnt` and `mountpoint`)
- `procps` package (for `pgrep`)
- `smartmontools` package (for `smartctl`)

### System-wide (optional)
Put `snapraid_aio.bb` somewhere in root's `$PATH`, e.g.:

```bash
sudo cp snapraid_aio.bb /usr/local/sbin/
sudo chmod +x /usr/local/sbin/snapraid_aio.bb
```

---

## ⚙️ Configuration

By default, `snapraid_aio.bb` looks for `/usr/local/etc/snapraid.conf`, then `/etc/snapraid.conf`.  
Override with `--config`:

```bash
snapraid_aio.bb --config /path/to/snapraid.conf
```

Your SnapRAID config should define:
- `data` or `disk` drives
- `parity` files
- `content` files

---

## 🔒 Safety Notes

- Must be run as **root** (or via `sudo`) for disk and S.M.A.R.T. checks.
- Lockfile: `/tmp/snapraid_aio.bb.lock` (auto-released when the process exits).
- If another instance is active, the script will exit safely.
- If one or more data or parity drives aren't mounted, the script will exit safely.
- If SMART tests show any disk errors, the script will exit safely.
---

## 🧩 Example Workflow

1. Run:
   ```bash
   sudo /usr/local/sbin/snapraid_aio.bb --config /etc/snapraid.conf
   ```
2. Script will:
    - Check mounts and disk health
    - Run `snapraid diff`
    - If changes exist, proceed with `sync` (or alert you)

---

## 📜 Exit Codes

| Code | Meaning                           |
|------|-----------------------------------|
| `0`  | Success                           |
| `2`  | Preflight failure (mounts/RO/etc) |
| `3`  | SMART check failed                |
| `4`  | SnapRAID missing                  |
| `5`  | Sync failed                       |
| `6`  | Scrub failed                      |
| `7`  | Unable to obtain a lock           |
| `8`  | Diff failed                       |


---

## 🔧 Development

### Project Layout
- `src/snapraid_aio.bb` — main Babashka script

### Testing

There are several common tests that need to be checked when modifying the script:
- Unmounted data and parity drives
- Running as a regular user
- Running concurrently
- Running when SnapRAID is already running
- Checking version
- Checking help
- Testing when snapraid diff indicates changes
- Testing when snapraid diff does not indicate changes

---

## 🤝 Contributing

Ideas for future improvements:
- Notifications (email, Slack, Telegram, etc.)
- Multiple SnapRAID config profiles
- Customizable log path
- Dry-run mode
- Force running sync even if SMART tests fail
- Better log management
- Make scrub optional
- Specify date/time format
- Save file permissions so they can be restored if you ever need to run `snapraid fix`
- Spin down disks with `hd-idle`
- Indicate if a newer version of the script is available

Fork, hack, and send a PR 🚀

---

## 📄 License

[MIT](LICENSE)

---

## 🖼️ Example Log Output

```
2025-09-25 18:43:01 [INFO] Running snapraid_aio.bb...
2025-09-25 18:43:01 [INFO] Script version 0.0.1
2025-09-25 18:43:01 [INFO] Using configuration from /etc/snapraid.conf
2025-09-25 18:43:01 [INFO] Logging to /var/log/snapraid_aio.bb.log
2025-09-25 18:43:01 [INFO] Lock file /tmp/snapraid_aio.bb.lock
2025-09-25 18:43:01 [INFO] Data drives ["/mnt/das1" "/mnt/das2" "/mnt/das3" "/mnt/das4" "/mnt/das5" "/mnt/das6"]
2025-09-25 18:43:01 [INFO] Parity drives ["/mnt/das-parity"]
2025-09-25 18:43:01 [INFO] Running snapraid diff...
2025-09-25 18:43:09 [INFO] Equal: 14957
2025-09-25 18:43:09 [INFO] Added: 1
2025-09-25 18:43:09 [INFO] Removed: 0
2025-09-25 18:43:09 [INFO] Updated: 0
2025-09-25 18:43:09 [INFO] Moved: 0
2025-09-25 18:43:09 [INFO] Copied: 0
2025-09-25 18:43:09 [INFO] Restored: 0
2025-09-25 18:43:09 [INFO] Running snapraid sync...
2025-09-25 18:49:23 [INFO] Running snapraid scrub...
2025-09-25 18:49:24 [INFO] Done
```

