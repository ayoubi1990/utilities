# utilities

A collection of desktop utilities. Each one lives in its own folder with its own source, installers and README.

| Utility | What it is | Releases |
|---|---|---|
| [velo](velo/) | Fast, resumable, multi-connection download manager for Windows and Linux, with browser integration and video-site support | tags `velo-v*` on the [Releases](../../releases) page |

## Conventions

- **Folder layout:** `<tool>/source` for code, and `<tool>/installer/{windows,linux,portable}` for packaging.
- **CI:** workflows live in `.github/workflows/<tool>.yml`, one per tool.
- **Releases:** tag a release as `<tool>-v<version>`, for example `velo-v1.0.0`. The tool's workflow builds and publishes its packages.
