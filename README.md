# Babashka Scripts for My Desktop Linux PC

This project contains the Babashka (Clojure) scripts that I use on my desktop Linux PC.
You may find some of them useful.

---

## Scripts

* snapraid_aio.bb — All-in-one SnapRAID script for managing a DAS (Direct Attached Storage).

---

## Building

    $ bb build

Scripts that you can run from the command line will be built in `out/`. Copy these to
somewhere on your `$PATH`, and you'll be able to run them like any other shell script.

---

## Project Structure

- `docs/` contains Markdown documents for each script.
- `out/` contains the built scripts that you install.
- `scripts/` contains the Babashka scripts.
- `src/` contains namespaces used by the Babashka scripts. Think of these like libraries.
- `.gitignore` defines items that should not be versioned in the git repository.
- `bb.edn` contains project configuration, including classpath definitions, the build task, etc. 
