# paprika-to-recipesage

**Import recipes from Paprika into RecipeSage**

A [Babashka](https://github.com/babashka/babashka)-powered tool that imports recipes from a `.paprikarecipes` file into RecipeSage.

My family maintains an extensive set of recipes in Paprika, but I like to run my own self-hosted instance
of RecipeSage for my own use. I want RecipeSage to have all of my family's Paprika recipes in it. When my family
has made changes to the recipes in Paprika, I want to export the recipes from Paprika and bring them into RecipeSage.
RecipeSage's import feature is not intelligent enough to detect when a recipe already  exists in RecipeSage, so it
happily re-imports every recipe from the `.paprikarecipes` file, resulting in duplicates of nearly all recipes.

I attempted to find ways within the RecipeSage UI to delete all the previously-imported Paprika recipes before importing
the latest `.paprikarecipes` file, but was unable to do it within the UI.

This script identifies Paprika recipes in RecipeSage as the recipes that have a "paprika" label assigned to them. It starts
by deleting all recipes in RecipeSage having the "paprika" label, then imports the recipes from the `.paprikarecipes` file and assigns
the "paprika" label to them.

---

## ✨ Features

- Imports recipes from `.paprikarecipes` file, replacing previously-imported Paprika recipes.
- Assigns a "paprika" label to imported recipes.
- Creates the "paprika" label if it doesn't exist.
- Prevents multiple instances of this script from running simultaneously.
- Sets recipe ratings to overcome a RecipeSage import bug.
- Extensive parameter checking.
- Logs activity to a log file.

---

## 🚀 Usage

```bash
paprika-to-recipesage [options]
```

### Options

| Flag                        | Description                                     |
|-----------------------------|-------------------------------------------------|
| `-d`, `--database DATABASE` | The name of the database.                       |
| `-h`, `--help`              | Display helpful information.                    |
| `-H`, `--host HOST`         | The database host.                              |
| `-f`, `--file FILE`         | The `.paprikarecipes` file to import. REQUIRED. |
| `-l`, `--label LABEL`       | The label to assign to imported recipes.        |
| `-p`, `--port PORT`         | The database port.                              |
| `-P`, `--password PASSWORD` | The database password.                          |
| `-u`, `--user USER`         | The database username.                          |
| `-v`, `--version`           | Display the version.                            |
| `-w`, `--web-base-url URL`  | The base URL of the RecipeSage website.         |
| `--web-user USER`           | The RecipeSage website username. REQUIRED.      |
| `--web-password PASSWORD`   | The RecipeSage website password. REQUIRED.      |

Examples:

```bash
paprika-to-recipesage --file /path/to/my-recipes.paprikarecipes --web-user "me@example.com" --web-password "examplepass"
paprika-to-recipesage --help
paprika-to-recipesage --version
```

---

## 📦 Installation

### Requirements
- Linux (tested on Linux Mint)
- [Babashka](https://github.com/babashka/babashka) (v1.3+ recommended)

### Build the Uberscript

    $ bb build

### Put the Uberscript on the $PATH

Put `out/paprika-to-recipesage` somewhere in`$PATH`, e.g.:

```bash
sudo cp ./out/paprika-to-recipesage ~/bin/
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
- `ide-stubs/` — Scripts to fix false errors in Intellij IDEA.
- `out/paprika-to-recipesage` — The script that you install.
- `scripts/paprika_to_recipesage.bb` — main Babashka script.
- `src/*` — Supporting namespaces.

---

## 🤝 Contributing

Ideas for future improvements:
- Output progress and summary information
- More extensive error checking and handling
- Automated testing

Fork, hack, and send a PR. 🚀

---

## 📄 License

[MIT](LICENSE)