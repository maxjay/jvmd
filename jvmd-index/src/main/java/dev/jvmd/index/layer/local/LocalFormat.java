package dev.jvmd.index.layer.local;

import dev.jvmd.index.layer.machine.Format;

/**
 * FORMAT of LOCAL (stage 2, B.8; stage 3, F): the machine FORMAT, the LOCAL layout version and full javac runtime version. The javac version is
 * in it because {@code Φ_src} depends on how javac resolves declarations; a different FORMAT is a cold boot, never a migration.
 */
public final class LocalFormat {
    /** Bumped when any byte layout in appendix B of stage 2 changes. */
    public static final int LAYOUT = 7;

    private LocalFormat() { }

    public static String of(Format machine) { return machine + ";local=" + LAYOUT + ";javac=" + Runtime.version() + ";locale=root"; }
}
