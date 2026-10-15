package dev.jvmd.index.layer.local;

import dev.jvmd.index.layer.machine.Format;

/**
 * FORMAT of LOCAL (stage 2, B.8): the machine FORMAT, the LOCAL layout version and the javac feature version. The javac version is
 * in it because {@code Φ_src} depends on how javac resolves declarations; a different FORMAT is a cold boot, never a migration.
 */
public final class LocalFormat {
    /**
     * Bumped when any byte layout in appendix B of stage 2 changes, or what a LOCAL record means: 24 runs annotation processors in
     * header compilation (amendments 4.8) and records a nest host only for targets of 11 or later; 25 gives sibling stubs their retained
     * annotations, which processors read. Numbers 6 to 23 were used, with other
     * meanings, by the unmerged stage 3 branch; skipping them keeps one FORMAT string from naming two codecs in a shared index.
     */
    public static final int LAYOUT = 25;

    private LocalFormat() { }

    public static String of(Format machine) { return machine + ";local=" + LAYOUT + ";javac=" + Runtime.version().feature(); }
}
