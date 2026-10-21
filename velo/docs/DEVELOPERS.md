# velo for developers

## Repository layout

```
velo/
  source/              application source (Java 21), browser extension, build scripts
  installer/windows/   .exe / .msi installers (jpackage + WiX)
  installer/linux/     .deb / .rpm packages (jpackage)
  installer/portable/  portable .zip (Windows) and .tar.gz (Linux)
  docs/                this file, the packaging guide, screenshots
.github/workflows/velo.yml   (at the repository root) CI: build, test, publish on velo-v* tags
```

## Build and run

Requires JDK 21+.

| | Windows (PowerShell) | Linux (bash) |
|---|---|---|
| Build + test | `source\build.ps1 -Test` | `bash source/build.sh --test` |
| Run | `source\velo-ui.cmd` | `java -jar source/velo.jar` |
| Installer | `installer\windows\package.ps1 -Type exe -Version 1.0.0` (or `-Type msi`) | `bash installer/linux/package.sh --type deb --version 1.0.0` (or `rpm`) |
| Portable | `installer\portable\make-portable.ps1 -Version 1.0.0` | `bash installer/portable/make-portable.sh --version 1.0.0` |

Everything is written to `dist/`.

**Build tools:**

- The scripts download yt-dlp, ffmpeg (LGPL) and deno from their official GitHub releases and check them against the publishers' SHA-256 checksums. Add `-NoVideoTools` / `--no-video-tools` to build without them.
- Windows installers need WiX 3.14. `installer\windows\fetch-wix.ps1` downloads a checksum-pinned copy if it isn't installed.
- Linux packages need `fakeroot` (for `.deb`) or `rpm` (for `.rpm`).

Packaging details (jpackage options, Launch4j, code signing, Swing/HiDPI notes): [PACKAGING.md](PACKAGING.md).

## Releasing

Push a `velo-v` tag. GitHub Actions builds and tests on Windows and Linux, then publishes every package to a GitHub Release:

```bash
git tag velo-v1.1.0
git push origin velo-v1.1.0
```

Then update the download links in [`velo/README.md`](../README.md). They point at exact file names, so replace `1.0.0` (and `velo-v1.0.0`) with the new version.

## Data locations

| Item | Windows | Linux |
|---|---|---|
| Download list and settings | `%APPDATA%\velo\downloads.properties` | `~/.local/share/velo/downloads.properties` |
| Updatable yt-dlp copy | `%APPDATA%\velo\tools` | `~/.local/share/velo/tools` |
| Unfinished downloads | `<file>.part` + `<file>.velo` next to the target | same |

`-Dvelo.home=<folder>` points velo at another data folder, which is useful for portable setups and tests.

## Command line

```
velo-cli [-o DIR] [-O FILE] [-c CONNECTIONS] [-l SPEED_LIMIT] [-H "Header: value"] URL...
```

For example: `velo-cli -o ~/Downloads -c 8 -l 2M https://example.com/file.iso`. Press Ctrl+C to pause; the same command resumes.

## Third-party components

See [source/THIRD-PARTY-NOTICES.txt](../source/THIRD-PARTY-NOTICES.txt).
