# Packaging velo for Windows

For Linux packages (`.deb`/`.rpm`) see `installer/linux/package.sh`; for portable archives, `installer/portable/`. GitHub Actions (`.github/workflows/velo.yml` at the repository root) runs all of them on every `velo-v*` tag.

This guide turns velo into a normal Windows product that end users install and run **without installing Java**:

- a single executable JAR
- a `velo.exe` launcher with the velo icon
- a Start menu entry and a desktop shortcut
- an optional `.exe` or `.msi` installer

It covers two routes:

| Route | What you get | Extra tools |
|---|---|---|
| **A. jpackage** (recommended) | Portable app folder, or an `.exe`/`.msi` installer with a private Java runtime, shortcuts, Start menu entry, uninstaller and upgrades | JDK 17+ (ships jpackage); WiX Toolset 3.14 for installers |
| **B. Launch4j** | `velo.exe` that wraps the JAR, plus a jlink runtime folder; you add your own installer (for example Inno Setup) | Launch4j, optionally Inno Setup |

Everything below runs from the project folder in PowerShell.

---

## 1. Build the single executable JAR

```powershell
source\build.ps1 -Test -Version 1.0.0 -Vendor "Your Company"
```

This produces one self-contained `velo.jar`:

- The FlatLaf library is unpacked into it, so there is no `lib\` folder to ship.
- `Main-Class` is `dev.velo.Main`.
- `META-INF/THIRD-PARTY-NOTICES.txt` and FlatLaf's licence are inside.

Try it on any PC that has Java 21 or later:

```powershell
java -jar velo.jar                         # desktop app (double-clicking velo.jar does the same)
java -jar velo.jar --cli https://example.com/file.zip   # command line
```

## 2. The application icon

`installer\windows\velo.ico` is a multi-size Windows icon (16 to 256 px). Windows needs all those sizes to look sharp in Explorer, on the taskbar and in shortcuts at any display scaling. To regenerate it from the app's own icon:

```powershell
source\build.ps1 -Test
java -cp "source\out\main;source\out\test" dev.velo.ui.MakeIcon installer\windows\velo.ico
```

To use your own artwork instead, export a `.ico` containing at least 16, 24, 32, 48, 64 and 256 px images, and save it as `installer\windows\velo.ico`.

---

## Route A: jpackage

jpackage ships with the JDK (`%JAVA_HOME%\bin\jpackage.exe`). It:

1. builds a trimmed private Java runtime with jlink,
2. creates `velo.exe` with your icon and version information, and
3. optionally wraps everything in an installer.

### A1. Portable app (no installer)

```powershell
installer\windows\package.ps1 -Version 1.0.0 -Vendor "Your Company"
```

The result is `dist\velo\`, which you can zip and ship:

```
dist\velo\
  velo.exe          desktop app (no console window)
  velo-cli.exe      command-line downloader (console)
  app\velo.jar
  runtime\          private Java runtime, about 70 MB
  extension\        browser extension
```

The script runs this command (shown here so you can run it by hand):

```powershell
jdeps --print-module-deps --ignore-missing-deps --multi-release 21 velo.jar
#  -> java.base,java.desktop   (+ jdk.crypto.ec, jdk.charsets, jdk.accessibility added by the script)

jpackage --type app-image `
  --name velo --app-version 1.0.0 --vendor "Your Company" `
  --description "Fast, resumable download manager" `
  --copyright "Copyright (c) 2026 Your Company" `
  --icon installer\windows\velo.ico `
  --input .cache\windows\input --main-jar velo.jar --main-class dev.velo.Main `
  --add-modules java.base,java.desktop,jdk.crypto.ec,jdk.charsets,jdk.accessibility `
  --jlink-options "--strip-debug --no-man-pages --no-header-files" `
  --java-options -Xmx512m `
  --app-content source\extension `
  --add-launcher velo-cli=installer\windows\velo-cli.properties `
  --dest dist
```

What the less obvious options do:

- `--input` is a folder that holds only `velo.jar`. jpackage copies everything in that folder into the app.
- `--add-modules`: jdeps can't see some modules that are only needed at run time. `jdk.crypto.ec` is needed for HTTPS to most sites, `jdk.charsets` covers non-UTF-8 text, and `jdk.accessibility` lets screen readers work.
- `--add-launcher` adds a second `.exe` in the same package. Here it creates a console launcher for the command-line downloader; see `installer\windows\velo-cli.properties`.
- `--app-content` ships extra folders, here the browser extension.

### A2. Installer (.exe or .msi) with shortcuts and a Start menu entry

jpackage builds installers with the **WiX Toolset**. On JDK 17 to 23 that means WiX **3.14** (`candle.exe` and `light.exe`); JDK 24 and later also accept WiX 4 and 5.

You don't have to install it. If `candle.exe` isn't on PATH, `package.ps1` runs `installer\windows\fetch-wix.ps1`, which:

1. downloads the official `wix314-binaries.zip`;
2. checks it against a pinned SHA-256 (verified once, by confirming its 68 files are byte-identical to the WiX 3.14.1 package on nuget.org);
3. unpacks it into `.cache\downloads\wix314`.

If you'd rather install WiX system-wide, run `winget install WiXToolset.WiXToolset` and add its `bin` folder to PATH.

Then build:

```powershell
installer\windows\package.ps1 -Type exe -Version 1.0.0 -Vendor "Your Company"   # dist\velo-1.0.0.exe
installer\windows\package.ps1 -Type msi -Version 1.0.0 -Vendor "Your Company"   # dist\velo-1.0.0.msi
```

On top of the options in A1, the script adds these Windows installer options:

```
--type exe | msi
--win-dir-chooser                lets the user choose the install folder
--win-menu --win-menu-group velo adds a Start menu entry in a "velo" folder
--win-shortcut                   adds a desktop shortcut...
--win-shortcut-prompt            ...with a checkbox to skip it
--win-per-user-install           installs for the current user, no admin rights needed
--win-upgrade-uuid b009807e-74f1-4f99-beb6-d09d24d6f137
--license-file LICENSE.txt       shows your EULA, if LICENSE.txt exists
```

Things to know:

- **Keep the upgrade UUID the same forever.** It's how Windows recognises a newer velo installer as an upgrade instead of a second product. **Increase `--app-version`** (for example 1.0.0, then 1.0.1) for every release, or the upgrade won't replace the old version.
- **To install for all users instead**, drop `--win-per-user-install`. It then installs under `C:\Program Files\velo` and needs admin rights.
- **Uninstalling** works through Windows **Settings > Apps**.
- **Which to ship:** `.msi` suits companies deploying with Intune or Group Policy, while `.exe` is friendlier for downloads from a website.

---

## Route B: Launch4j

Launch4j wraps `velo.jar` into a `velo.exe`. It doesn't create installers or shortcuts, so pair it with Inno Setup or WiX if you need them.

1. Prepare the runtime and JAR:

   ```powershell
   installer\windows\package.ps1 -Type launch4j
   ```

   This produces `dist\launch4j\velo.jar`, `runtime\` (built with jlink, using the same modules as above) and `extension\`. The jlink command it runs is:

   ```powershell
   jlink --add-modules java.base,java.desktop,jdk.crypto.ec,jdk.charsets,jdk.accessibility `
         --output dist\launch4j\runtime --strip-debug --no-man-pages --no-header-files
   ```

2. Install Launch4j from https://launch4j.sourceforge.net and build the exe:

   ```powershell
   & "C:\Program Files (x86)\Launch4j\launch4jc.exe" installer\windows\launch4j.xml
   ```

   Here's what `installer\windows\launch4j.xml` sets up:

   - **Embedded JAR:** `velo.jar` is wrapped inside the `.exe`.
   - **GUI header:** the `.exe` runs without a console window.
   - **Bundled runtime:** `<jre><path>runtime</path>` makes it use the runtime shipped next to the `.exe`, so users don't need Java installed.
   - **Single-instance mutex:** Windows refuses to start a second copy.
   - **File details:** the version information shown in Explorer's file properties.
   - **Custom manifest:** `installer\windows\velo.exe.manifest` declares per-monitor DPI awareness and `asInvoker`, so the app needs no admin prompt.

3. Ship the `dist\launch4j\` folder. For an installer, point Inno Setup at it, and create the Start menu and desktop shortcuts in the Inno script with the `[Icons]` section.

---

## 3. Code signing (needed for a commercial product)

Unsigned `.exe`/`.msi` files trigger Microsoft Defender SmartScreen's "Windows protected your PC" warning. Corporate application-control policies may block them outright, as happens on the laptop velo was developed on. To avoid this:

1. Buy a code-signing certificate from a certificate authority. An OV or EV certificate is fine; EV builds SmartScreen reputation immediately.
2. Sign every executable and the installer with `signtool` from the Windows SDK:

```powershell
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /a dist\velo\velo.exe dist\velo\velo-cli.exe
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /a dist\velo-1.0.0.msi
```

For installers, sign the app image's `.exe` files before packaging them into the installer, then sign the installer itself. With jpackage, build the image first, sign it, and pass it back with `jpackage --type exe --app-image dist\velo ...`.

---

## 4. Swing best practices for Windows, as velo applies them

**Look and feel**

- velo uses **FlatLaf** in light or dark, and offers **View > Windows native look**, which is `UIManager.getSystemLookAndFeelClassName()`. If FlatLaf fails to load for any reason, `Theme.apply` falls back to the native Windows look rather than Swing's dated default, Metal.
- To switch the look while the app is running, call `SwingUtilities.updateComponentTreeUI` for every open window. `Theme.apply` does this.
- Set the look and feel **before** creating any component, on the Event Dispatch Thread (`SwingUtilities.invokeLater`), as `App.main` does.
- Colours that depend on the theme are read at paint time (`Theme.muted()`, `Theme.hairline()`). That way they follow theme switches instead of being frozen when a component is created.

**High-DPI displays**

- Java 9+ is **per-monitor DPI aware** on Windows (JEP 263). Swing is painted at the monitor's scale (125%, 150%, 200%) and rescales when the window moves to another monitor. `App` sets `sun.java2d.uiScale.enabled=true` explicitly, so a launcher can't switch it off.
- Don't force a scale with `-Dsun.java2d.uiScale=…`; let Windows decide.
- **Icons:** velo draws its icons from Windows' **Segoe Fluent Icons** font and as Java2D shapes, so they're sharp at every scale. If you use bitmaps, supply 1x/1.5x/2x versions through a `BaseMultiResolutionImage`, or use SVG icons (FlatLaf's `FlatSVGIcon`, in the flatlaf-extras library).
- **Window icons:** `setIconImages` is given several sizes (16 to 64 px), so the taskbar and Alt+Tab pick a sharp one.
- **Sizes:** give them in logical pixels (`new Dimension(76, 62)`); Java multiplies them by the scale factor. Prefer layout managers (BorderLayout, GridBagLayout) over absolute positions, so translated text and larger fonts still fit.
- **Text:** `sun.awt.useSystemAAFontSettings=on` uses the user's ClearType settings, and velo uses the Windows UI font (Segoe UI).
- **Launch4j manifest:** a Launch4j `.exe` should declare `PerMonitorV2` DPI awareness in its manifest (see `installer\windows\velo.exe.manifest`). Otherwise Windows may stretch the window as a bitmap and blur it. jpackage launchers need nothing extra.

**Other Windows conventions**

- **User data:** store it in `%APPDATA%\<app>`, never next to the `.exe`, because Program Files is read-only for normal users. velo also accepts `-Dvelo.home=<folder>` for portable installs.
- **One instance:** velo uses a lock file plus a local hand-off, so a second launch focuses the running window and passes its links on.
- **Threads:** do long work (network, file I/O, running yt-dlp) off the Event Dispatch Thread and update the UI with `SwingUtilities.invokeLater`. Keep timers that touch Swing as `javax.swing.Timer`.
- **Keyboard:** add accelerators and mnemonics, keep the focus order sensible, and put the default button on dialogs (`getRootPane().setDefaultButton`), with Esc closing them.

---

## 5. Release checklist

1. Update the version: `-Version 1.2.0`, plus `<fileVersion>`/`<productVersion>` in `launch4j.xml` if you use Launch4j.
2. Run `source\build.ps1 -Test`; every test should pass.
3. Run `installer\windows\package.ps1 -Type msi` (and/or `exe`).
4. Sign the `.exe` files and the installer.
5. Test on a clean Windows VM with no Java installed. Check that the installer offers the shortcut, the Start menu entry appears, the app starts, an upgrade from the previous version works, and uninstalling works.
6. Ship `THIRD-PARTY-NOTICES.txt` (it's already inside `velo.jar`) and your EULA.
