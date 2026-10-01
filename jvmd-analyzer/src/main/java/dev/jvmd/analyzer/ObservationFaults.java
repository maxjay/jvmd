package dev.jvmd.analyzer;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Observations behind persisted certificates (corrective pass, C3). A failed observation is UNKNOWN:
 * it throws {@link Unavailable} and never becomes an identity, so two successive failures can never
 * compare equal and reuse a record. Only an established absence ({@code NoSuchFile}, or a path through a
 * regular file) is a negative observation.
 *
 * {@link #inject} and {@link #captureBarrier} are test seams, never set in production: tests make
 * chosen paths fail without relying on filesystem permissions (privileged CI ignores them), and pause a
 * capture between attribution and its evidence reads to race mutations against it (C4).
 */
public final class ObservationFaults {
    /** UNKNOWN: the input could not be observed. */
    public static final class Unavailable extends IOException {
        public Unavailable(String what,Throwable cause){super(what,cause);}
    }
    private static volatile Predicate<Path> failing=path->false;
    private static volatile java.util.function.Consumer<Path> barrier=path->{};
    private ObservationFaults(){}

    /** Test seam: every observation of a path matching {@code failing} fails as if unreadable. */
    public static void inject(Predicate<Path> failing){ObservationFaults.failing=Objects.requireNonNull(failing);}
    /** Test seam: runs on the owner thread after a unit is attributed and before its record is captured. */
    public static void captureBarrier(java.util.function.Consumer<Path> barrier){ObservationFaults.barrier=Objects.requireNonNull(barrier);}
    public static void clear(){failing=path->false;barrier=path->{};}
    static void beforeCapture(Path file){barrier.accept(file);}

    static void check(Path path)throws Unavailable{
        if(failing.test(path.toAbsolutePath().normalize()))throw new Unavailable("injected observation failure: "+path,null);
    }
    /** File attributes, {@code null} for an established absence, {@link Unavailable} otherwise. */
    static BasicFileAttributes attributes(Path path)throws Unavailable{
        check(path);
        try{return Files.readAttributes(path,BasicFileAttributes.class);}
        catch(NoSuchFileException|NotDirectoryException absent){return null;}
        catch(FileSystemException failure){
            // ENOTDIR: a path through a regular file names nothing.
            if("Not a directory".equals(failure.getReason()))return null;
            throw new Unavailable("unreadable: "+path,failure);
        }
        catch(IOException|SecurityException failure){throw new Unavailable("unreadable: "+path,failure);}
    }
    public static boolean regularFile(Path path)throws Unavailable{var value=attributes(path);return value!=null&&value.isRegularFile();}
    public static boolean directory(Path path)throws Unavailable{var value=attributes(path);return value!=null&&value.isDirectory();}
}
