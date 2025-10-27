# new-bb

**Create a new Babashka project**

---

## ✨ Features

- Creates a basic project structure.
- Creates the main script at `scripts/main.bb`
- Creates a build task to generate `out/main`

---

## 🚀 Usage

```bash
new-bb [options]
```

### Options

| Flag                | Description                             |
|---------------------|-----------------------------------------|
| `-h`, `--help`      | Display helpful information.            |
| `-d`, `--directory` | The directory to create the project in. |
| `-v`, `--version`   | Display the version.                    |

Examples:

```bash
bb-new --directory /tmp/foo
imgur --help
imgur --version
```

---

## 📦 Installation

### Requirements
- Linux (tested on Linux Mint)
- [Babashka](https://github.com/babashka/babashka) (v1.3+ recommended)

### Build the Uberscript

    $ bb build

### Put the Uberscript on the $PATH

Put `out/new-bb` somewhere in`$PATH`, e.g.:

```bash
sudo cp ./out/new-bb ~/bin/
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
- `out/new-bb` — The script that you install.
- `scripts/new_bb.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

---

## 🤝 Contributing

Ideas for future improvements:
- Process `--help` from the command line in `-main`. 

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)