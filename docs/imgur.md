# imgur

**Upload images to Imgur**

A [Babashka](https://github.com/babashka/babashka)-powered tool that uploads images from either the filesystem or a URL to Imgur.

---

## ✨ Features

- Upload a local file to Imgur.
- Upload a file from a URL to Imgur.
- Safety
  - Checks that the file or URL are valid.

---

## 🚀 Usage

```bash
imgur [options]
```

### Options

| Flag              | Description                               |
|-------------------|-------------------------------------------|
| `-h`, `--help`    | Display helpful information.              |
| `-i`, `--image`   | The image file or URL to upload to Imgur. |
| `-v`, `--version` | Display the version.                      |

The filename or URL can also be provided to stdin instead of using the `-i` or `--image` option.

Examples:

```bash
imgur --image /path/to/image.png
echo /path/to/image.png | imgur
imgur -i "https://www.example.com/image.png"
echo https://www.example.com/image.png | imgur
imgur --help
imgur --version
```
---

## 📦 Installation

### Requirements
- Linux (tested on Linux Mint)
- [Babashka](https://github.com/babashka/babashka) (v1.3+ recommended)
- `imagemagick` package (for `identify`)
- `curl` package

### Build the Uberscript

    $ bb build

### Put the Uberscript on the $PATH

Put `out/imgur` somewhere in`$PATH`, e.g.:

```bash
sudo cp ./out/imgur ~/bin/
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
- `out/imgur` — The script that you install.
- `scripts/imgur.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

---

## 🤝 Contributing

Ideas for future improvements:
- Uploading multiple images into a single post. 

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)