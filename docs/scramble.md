# scramble

**Encrypt or decrypt files using a password.**

A [Babashka](https://github.com/babashka/babashka)-powered tool that encrypts or decrypts a file with a password using GnuPG.

---

## ✨ Features

- Encrypt and decrypt files.
- Automatically determine whether to encrypt or decrypt.
- Delete original file when encryption or decryption is successful.
- Prevent deleting the original file with the `--keep` option.
- Safety
    - Checks that the file is valid.
    - Don't delete the original file if there is any error.
    - Remove write permission from encrypted file to prevent modification.

---

## 🚀 Usage

```bash
scramble [options] [file]
```

### Options

| Flag              | Description                            |
|-------------------|----------------------------------------|
| `-h`, `--help`    | Display helpful information.           |
| `-k`, `--keep`    | Prevent deletion of the original file. |
| `-v`, `--version` | Display the version.                   |

Examples:

```bash
scramble /path/to/file.txt
scramble --help
scramble --version
```
---

## 📦 Installation

### Requirements
- Linux (tested on Linux Mint)
- [Babashka](https://github.com/babashka/babashka) (v1.3+ recommended)
- `gpg` package (for `gpg`)

### Build the Uberscript

    $ bb build

### Put the Uberscript on the $PATH

Put `out/scramble` somewhere in`$PATH`, e.g.:

```bash
sudo cp ./out/scramble ~/bin/
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
- `out/scramble` — The script that you install.
- `scripts/scramble.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

---

## 🤝 Contributing

Ideas for future improvements:
- Encrypt or decrypt multiple files at once.

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)