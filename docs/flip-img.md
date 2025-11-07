# flip-img

**Flips an image**

A [Babashka](https://github.com/babashka/babashka)-powered tool that flips an image—from either the filesystem or a URL—horizontally or vertically.

---

## ✨ Features

- Flip an image horizontally or vertically.
- Obtain the image from the filesystem, or download it from a URL.

---

## 🚀 Usage

```bash
flip-img [options] [file|url]
```

### Options

| Flag                 | Description                            |
|----------------------|----------------------------------------|
| `-h`, `--help`       | Display helpful information.           |
| `-o`, `--output`     | The file to output.                    |
| `-t`, `--vertical`   | Flip the image vertically.             |
| `-v`, `--version`    | Display the version.                   |
| `-z`, `--horizontal` | Flip the image horizontally (default). |

The filename or URL can also be provided to stdin instead of as an argument.

Examples:

```bash
flip-img -o /path/to/image-flipped.png /path/to/image.png
echo /path/to/image.png | flip-img --vertical --output /path/to/image-flipped.png
flip-img --output /path/to/image-flipped.png "https://www.example.com/image.png"
echo https://www.example.com/image.png | flip-img --output /path/to/image-flipped.png
flip-img --help
flip-img --version
```
---

## 📦 Installation

### Requirements
- Linux (tested on Linux Mint)
- [Babashka](https://github.com/babashka/babashka) (v1.3+ recommended)
- `imagemagick` package (for `command`)

### Build the Uberscript

    $ bb build

### Put the Uberscript on the $PATH

Put `out/flip-img` somewhere in`$PATH`, e.g.:

```bash
sudo cp ./out/flip-img ~/bin/
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
- `out/flip-img` — The script that you install.
- `scripts/flip_img.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

---

## 🤝 Contributing

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)