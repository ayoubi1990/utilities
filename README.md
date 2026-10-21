<h1 align="center">utilities</h1>

<p align="center">Free desktop tools for Windows and Linux.</p>

---

<table>
  <tr>
    <td width="120" align="center"><img src="velo/installer/linux/velo.png" width="80" alt="velo"></td>
    <td>
      <h3><a href="velo/">velo</a> — download manager</h3>
      Download files and videos faster over several connections at once, pause and resume anytime, and grab
      videos from YouTube and 1000+ sites in the quality you choose.<br><br>
      <a href="https://github.com/ayoubi1990/utilities/releases/download/velo-v1.0.0/velo-1.0.0.exe"><img src="https://img.shields.io/badge/Download%20for%20Windows-149%20MB-0078D6?style=for-the-badge" alt="Download for Windows"></a>
      <a href="velo/#-other-downloads"><img src="https://img.shields.io/badge/Linux%20%26%20portable-more%20downloads-555555?style=for-the-badge" alt="Linux and portable downloads"></a>
    </td>
  </tr>
</table>

---

## 🛠️ For contributors

Every tool follows the same layout, so adding a new one is predictable.

| What | Where |
|---|---|
| Source code | `<tool>/source/` |
| Installers | `<tool>/installer/windows/`, `<tool>/installer/linux/`, `<tool>/installer/portable/` |
| Build and release automation | `.github/workflows/<tool>.yml` |
| Developer guide | `<tool>/docs/DEVELOPERS.md` (for example [velo's](velo/docs/DEVELOPERS.md)) |

**To publish a new version,** push a tag named `<tool>-v<version>`. GitHub then builds, tests and publishes that tool's downloads:

```bash
git tag velo-v1.1.0
git push origin velo-v1.1.0
```
