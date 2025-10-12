# youtube-download

**Download videos from YouTube**

A [Babashka](https://github.com/babashka/babashka)-powered tool that downloads individual videos or multiple videos from a channel.

---

## ✨ Features

- Use the URL for an individual video, or for a channel.
- Choose the directory to save to.
- Safety
    - Checks that URLs and directories are valid.

---

## 🚀 Usage

```bash
youtube-download [options]
```

### Options

| Flag                    | Description                  |
|-------------------------|------------------------------|
| `-d`, `--directory DIR` | The directory to save in.    |
| `-h`, `--help`          | Display helpful information. |
| `-u`, `--url URL`       | The video or channel URL.    |
| `-v`, `--version`       | Display the version.         |

Examples:

```bash
youtube-download --url https://example.com/v/1234567 --directory /tmp
youtube-download --help
youtube-download --version
```

---

## 📦 Installation

### Requirements
- Linux (tested on Linux Mint)
- [Babashka](https://github.com/babashka/babashka) (v1.3+ recommended)
- `yt-dlp` package (for `yt-dlp`)
- `curl` package

### Build the Uberscript

    $ bb build

### Put the Uberscript on the $PATH

Put `out/youtube-download` somewhere in`$PATH`, e.g.:

```bash
sudo cp ./out/youtube-download ~/bin/
```

---

## 📜 Exit Codes

| Code | Meaning                   |
|------|---------------------------|
| `0`  | Success                   |
| `1`  | Failed                    |

---

## 🔧 Development

### Project Layout
- `docs/*.md` — Documentation for each script.
- `out/youtube-download` — The script that you install.
- `scripts/youtube_download.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

---

## 🤝 Contributing

Ideas for future improvements:
- Allow overriding many of the yt-dlp config options on the command line.
- Warn if the user's YouTube cookies file is old.

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)