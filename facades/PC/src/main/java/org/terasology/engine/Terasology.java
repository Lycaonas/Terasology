// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine;

import picocli.CommandLine;

/**
 * Class providing the main() method for launching Terasology as a PC app.
 * <p>
 * Through the following launch arguments default locations to store logs and
 * game saves can be overridden, by using the current directory or a specified
 * one as the home directory. Furthermore, Terasology can be launched headless,
 * to save resources while acting as a server or to run in an environment with
 * no graphics, audio or input support. Additional arguments are available to
 * reload the latest game on startup and to disable crash reporting.
 * <p>
 * Available launch arguments:
 *
 * <table summary="Launch arguments">
 * <tbody>
 * <tr><td>-homedir</td><td>Use the current directory as the home directory.</td></tr>
 * <tr><td>-homedir=path</td><td>Use the specified path as the home directory.</td></tr>
 * <tr><td>-headless</td><td>Start headless.</td></tr>
 * <tr><td>-loadlastgame</td><td>Load the latest game on startup.</td></tr>
 * <tr><td>-createlastgame</td><td>Recreates the world of the latest game with a new save file on startup.</td></tr>
 * <tr><td>-noSaveGames</td><td>Disable writing of save games.</td></tr>
 * <tr><td>-noCrashReport</td><td>Disable crash reporting.</td></tr>
 * <tr><td>-noSound</td><td>Disable sound.</td></tr>
 * <tr><td>-noSplash</td><td>Disable splash screen.</td></tr>
 * <tr><td>-serverPort=xxxxx</td><td>Change the server port.</td></tr>
 * </tbody>
 * </table>
 * <p>
 * When used via command line an usage help and some examples can be obtained via:
 * <br><br>
 * terasology -help    or    terasology /?
 *
 */

public final class Terasology {

    private Terasology() {
    }

    public static void main(String[] args) {
        new CommandLine(new TerasologyCommand()).execute(args);
    }

}
