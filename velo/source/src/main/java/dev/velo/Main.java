package dev.velo;

import java.util.Arrays;

/**
 * The single entry point of velo.jar / velo.exe:
 * <pre>
 *   velo                      open the desktop app
 *   velo URL...               open the app and add the links
 *   velo --cli [options] URL  download from the command line
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--cli")) {
            dev.velo.cli.Main.main(Arrays.copyOfRange(args, 1, args.length));
        } else {
            dev.velo.ui.App.main(args);
        }
    }
}
