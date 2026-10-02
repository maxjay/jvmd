package dev.jvmd.tests;

import dev.jvmd.boot.cold.machine.MachineColdBoot;
import java.nio.file.Path;

/** Cold-boots MACHINE in a process of its own and prints the root: {@code GENERATION REPOSITORY JDK}. */
public final class MachineRootProcess {
    public static void main(String[] args)throws Exception{
        try(var storage=new MachineColdBoot(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]),32L*1024*1024).run()){
            System.out.println("ROOT "+storage.machine().root().orElseThrow().identity());
        }
    }
}
