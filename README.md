# Babashka Scripts for My Desktop Linux PC

This project contains the Babashka (Clojure) scripts that I use on my desktop Linux PC.
You may find some of them useful.

---

## Scripts

* flip-img — Flip an image horizontally or vertically from the command line.
* imgur — Upload images to Imgur from the command line.
* new-bb — Create a new Babashka project.
* paprika-to-recipesage — Import recipes from Paprika to RecipeSage.
* postgresql-backup — Dump a postgresql database.
* resize-img — resize an image by width or percentage (maintaining aspect ratio) from the command line.
* rotate-img — rotate an image from the command line.
* snapraid-aio — All-in-one SnapRAID script for managing a DAS (Direct Attached Storage).
* youtube-download — Download YouTube videos by channel or video URL.

Every script can be run with the `--help` or `-h` option to learn how to use it.

---

## Building

    $ bb build

Scripts that you can run from the command line will be built in `out/`. Copy these to
somewhere on your `$PATH`, and you'll be able to run them like any other shell script.

---

## Developing

### Project Structure

- `docs/` contains Markdown documents for each script.
- `ide-stubs/` contains namespaces to eliminate errors in the IDE.
- `out/` contains the built scripts that you install.
- `scripts/` contains the Babashka scripts.
- `src/` contains namespaces used by the Babashka scripts. Think of these like libraries.
- `.gitignore` defines items that should not be versioned in the git repository.
- `bb.edn` contains project configuration, including classpath definitions, the build task, etc. 

### IntelliJ IDEA + Cursive setup for `.bb` scripts

To get IntelliJ IDEA / Cursive to treat Babashka scripts correctly:

#### 1. Enable syntax highlighting for `.bb` files
- Go to **File → Settings → Editor → File Types**
- Select **Clojure**
- Add `*.bb` under **Registered Patterns**
- Click **Apply**

This ensures new `.bb` files open with Clojure syntax highlighting instead of plain text.

#### 2. Mark `.bb` files as Babashka scripts (needed for resolution)
- In the **Project** tool window, type `file:*.bb` in the search bar
- Select all `.bb` files
- Right-click → **Mark as Babashka script**

Cursive will now resolve symbols against the Babashka runtime.  
