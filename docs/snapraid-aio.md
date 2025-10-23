# snapraid-aio

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
snapraid-aio [options]
```

### Options

| Flag                    | Description                                            |
|-------------------------|--------------------------------------------------------|
| `-c`, `--config`        | Path to SnapRAID configuration file.                   |
| `-h`, `--help`          | Display helpful information.                           |
| `-i`, `--ignore-smart`  | Continue even when S.M.A.R.T. tests indicate problems. |
| `-p`, `--scrub-percent` | Percentage of blocks to scrub (0-100). Default 10.     |
| `-d`, `--skip-diff`     | Don't run SnapRAID diff.                               |
| `-s`, `--skip-scrub`    | Don't run SnapRAID scrub.                              |
| `-v`, `--version`       | Display the version.                                   |

Examples:

```bash
snapraid-aio --config /etc/snapraid.conf --scrub-percent 20 --ignore-smart
snapraid-aio --help
snapraid-aio --version
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

### Build the Uberscript

    $ bb build

### System-wide (optional)

Put `out/snapraid-aio` somewhere in root's `$PATH`, e.g.:

```bash
sudo cp ./out/snapraid-aio /usr/local/sbin/
```

---

## ⚙️ Configuration

By default, `snapraid-aio` looks for `/usr/local/etc/snapraid.conf`, then `/etc/snapraid.conf`.  
Override with `--config`:

```bash
snapraid-aio --config /path/to/snapraid.conf
```

Your SnapRAID config must define at a minimum:
- `data` or `disk` drives
- `parity` files
- `content` files

---

## 🔒 Safety Notes

- Must be run as **root** (or via `sudo`) for disk and S.M.A.R.T. checks.
- Lockfile: `/tmp/snapraid-aio.lock` (auto-released when the process exits).
- If another instance is active, the script will exit safely.
- If one or more data or parity drives aren't mounted, the script will exit safely.
- If S.M.A.R.T. tests show any disk errors, the script will exit safely (unless `--skip-smart` is used).
---

## 🧩 Example Workflow

1. Run:
   ```bash
   sudo /usr/local/sbin/snapraid-aio --config /etc/snapraid.conf
   ```
2. Script will:
    - Check mounts and disk health
    - Run `snapraid diff`
    - If changes exist, proceed with `sync` (or alert you)
    - Saves file permissions in case they need to be restored

---

## 📜 Exit Codes

| Code | Meaning                   |
|------|---------------------------|
| `0`  | Success                   |
| `1`  | Failed                    |
| `2`  | Preflight failed          |
| `3`  | S.M.A.R.T. check failed   |
| `4`  | SnapRAID failed           |
| `5`  | Sync failed               |
| `6`  | Scrub failed              |
| `7`  | Unable to obtain a lock   |
| `8`  | Diff failed               |
| `9`  | Saving permissions failed |

---

## 🔧 Development

### Project Layout
- `docs/*.md` — Documentation for each script.
- `out/` — The built script that you install.
- `scripts/snapraid_aio.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

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
- Testing that permissions are saved, and can be restored

---

## 🤝 Contributing

Ideas for future improvements:
- Spin down disks with `hd-idle`
- Notifications (email, Slack, Telegram, healthchecks.io, etc.)
- Dry-run mode
- Better log management (rotate logs)
- Customizable log path
- Specify date/time format for log entries
- Indicate if a newer version of the script is available

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)

---

## 🖼️ Example Log Output

```
2025-09-25 18:43:01 [INFO] Running snapraid-aio...
2025-09-25 18:43:01 [INFO] Script version 0.0.1
2025-09-25 18:43:01 [INFO] Using configuration from /etc/snapraid.conf
2025-09-25 18:43:01 [INFO] Logging to /var/log/snapraid-aio.log
2025-09-25 18:43:01 [INFO] Lock file /tmp/snapraid-aio.lock
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

