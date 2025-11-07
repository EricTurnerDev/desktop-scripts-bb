# rotate-img

**Rotates an image**

A [Babashka](https://github.com/babashka/babashka)-powered tool that rotates an image from either the filesystem or a URL.

---

## ✨ Features

- Rotate an image.
- Obtain the image from the filesystem, or download it from a URL.

---

## 🚀 Usage

```bash
rotate-img [options] [file|url]
```

### Options

| Flag              | Description                                                             |
|-------------------|-------------------------------------------------------------------------|
| `-d`, `--degrees` | The number of degrees to rotate the image by.                           |
| `-h`, `--help`    | Display helpful information.                                            |
| `-o`, `--output`  | The file to output.                                                     |
| `-v`, `--version` | Display the version.                                                    |

The filename or URL can also be provided to stdin instead of as an argument.

Examples:

```bash
rotate-img --degrees 90 -o ./example-rotated.jpg ./example.jpg
rotate-img -d "-90" -o ./example-180.jpg http://example.com/example.jpg
echo http://example.com/example.jpg | rotate-img -d "-90" -o ./example-rotated.jpg
rotate-img --help
rotate-img --version
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

Put `out/rotate-img` somewhere in`$PATH`, e.g.:

```bash
sudo cp ./out/rotate-img ~/bin/
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
- `out/rotate-img` — The script that you install.
- `scripts/rotate_img.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

---

## 🤝 Contributing

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)