# resize-img

**Resizes an image**

A [Babashka](https://github.com/babashka/babashka)-powered tool that resizes an image—from either the filesystem or a URL—by width or percent.

---

## ✨ Features

- Resize an image by width or percent.
- Obtain the image from the filesystem, or download it from a URL.

---

## 🚀 Usage

```bash
resize-img [options] [file|url]
```

### Options

| Flag              | Description                                                             |
|-------------------|-------------------------------------------------------------------------|
| `-h`, `--help`    | Display helpful information.                                            |
| `-o`, `--output`  | The file to output.                                                     |
| `-p`, `--percent` | The percent to resize the image by.                                     |
| `-v`, `--version` | Display the version.                                                    |
| `-w`, `--width`   | The width of the output image. Aspect ratio of the image is maintained. |

The filename or URL can also be provided to stdin instead of as an argument.

Examples:

```bash
resize-img --width 1024 -o ./example-1024w.jpg ./example.jpg
resize-img -p 50 -o ./example-50pct.jpg http://example.com/example.jpg
echo http://example.com/example.jpg | resize-img -w 256 -o ./example-256w.jpg
resize-img --help
resize-img --version
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

Put `out/resize-img` somewhere in`$PATH`, e.g.:

```bash
sudo cp ./out/resize-img ~/bin/
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
- `out/resize-img` — The script that you install.
- `scripts/resize_img.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

---

## 🤝 Contributing

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)