package dev.jvmd.smoke;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Implements section 9: JDI redefinition, retaining chains, and strict AOT coexistence. */
public final class JdiSmoke {
    private static final String TARGET = "dev.jvmd.smoke.SwapTarget";
    private static final String MAIN = "dev.jvmd.smoke.Debuggee";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 25) {
            throw new IllegalStateException("Smoke controller requires JDK 25, got " + Runtime.version());
        }
        if (args.length != 5) {
            throw new IllegalArgumentException("java_home fixture_jar replacement_dir enhanced aot");
        }
        Path javaHome = Path.of(args[0]).toAbsolutePath();
        Path classes = Path.of(args[1]).toAbsolutePath();
        Path replacement = Path.of(args[2]).toAbsolutePath();
        boolean enhanced = Boolean.parseBoolean(args[3]);
        boolean aot = Boolean.parseBoolean(args[4]);
        List<String> vmArgs = new ArrayList<>();
        vmArgs.add(javaHome.resolve("bin/java").toString());
        if (enhanced) vmArgs.add("-XX:+AllowEnhancedClassRedefinition");
        if (aot) {
            Path cache = Files.createTempDirectory(classes.getParent(), "aot-smoke-")
                    .resolve("debuggee.aot");
            var training = new ArrayList<>(vmArgs);
            training.add("-XX:AOTCacheOutput=" + cache);
            training.addAll(List.of("-cp", classes.toString(), MAIN, "train"));
            runTraining(training);
            if (!Files.isRegularFile(cache) || Files.size(cache) == 0) {
                throw new AssertionError("Training produced no AOT cache: " + cache);
            }
            vmArgs.add("-XX:AOTCache=" + cache);
            vmArgs.add("-XX:AOTMode=on");
        }
        vmArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0");
        vmArgs.addAll(List.of("-cp", classes.toString(), MAIN));
        System.out.println("COMMAND " + String.join(" ", vmArgs));
        Process process = new ProcessBuilder(vmArgs).redirectErrorStream(true).start();
        var port = new CompletableFuture<String>();
        var ready = new CompletableFuture<Void>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = lines.readLine()) != null;) {
                    System.out.println("debuggee: " + line);
                    if (line.startsWith("Listening for transport dt_socket at address: ")) {
                        port.complete(line.substring(line.lastIndexOf(' ') + 1));
                    }
                    if (line.equals("READY")) ready.complete(null);
                }
                var early = new IllegalStateException("Debuggee ended before readiness");
                port.completeExceptionally(early);
                ready.completeExceptionally(early);
            } catch (Exception failure) {
                port.completeExceptionally(failure);
                ready.completeExceptionally(failure);
            }
        });
        VirtualMachine vm = null;
        try {
            String address = port.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            ready.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                    .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                    .findFirst().orElseThrow();
            var parameters = connector.defaultArguments();
            parameters.get("hostname").setValue("127.0.0.1");
            parameters.get("port").setValue(address);
            parameters.get("timeout").setValue("5000");
            long start = System.nanoTime();
            vm = connector.attach(parameters);
            System.out.printf("attach_ms=%.3f%n", (System.nanoTime() - start) / 1_000_000.0);
            System.out.println("target=" + vm.description().replace('\n', ' '));
            require(vm.canRedefineClasses(), "Target cannot redefine classes");
            require(vm.canGetInstanceInfo(), "Target cannot inspect instances");

            ClassType target = (ClassType) vm.classesByName(TARGET).getFirst();
            var main = vm.classesByName(MAIN).getFirst();
            var location = main.methodsByName("checkpoint").getFirst().allLineLocations().getFirst();
            var breakpoint = vm.eventRequestManager().createBreakpointRequest(location);
            breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            breakpoint.enable();
            process.getOutputStream().write('p');
            process.getOutputStream().flush();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            boolean tested = false;
            while (System.nanoTime() < deadline && !tested) {
                EventSet events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                for (var event : events) {
                    if (!(event instanceof BreakpointEvent stopped)) continue;
                    breakpoint.disable();
                    var root = (ObjectReference) main.getValue(main.fieldByName("root"));
                    var leaf = (ObjectReference) root.getValue(root.referenceType().fieldByName("leaf"));
                    var referring = leaf.referringObjects(10);
                    require(referring.stream().anyMatch(o -> o.uniqueID() == root.uniqueID()),
                            "Retaining Holder is absent from referringObjects(10)");
                    System.out.println("PASS canGetInstanceInfo and retained Leaf -> Holder");

                    byte[] updated = Files.readAllBytes(replacement.resolve("dev/jvmd/smoke/SwapTarget.class"));
                    long redefineStart = System.nanoTime();
                    vm.redefineClasses(Map.of(target, updated));
                    System.out.printf("hotswap_ms=%.3f%n", (System.nanoTime() - redefineStart) / 1_000_000.0);
                    String methodName = enhanced ? "added" : "value";
                    var methods = target.methodsByName(methodName, "()I");
                    require(methods.size() == 1, "Expected one callable " + methodName + "()I");
                    var value = (IntegerValue) target.invokeMethod(stopped.thread(), methods.getFirst(),
                            List.of(), ClassType.INVOKE_SINGLE_THREADED);
                    require(value.intValue() == (enhanced ? 42 : 2), "Wrong replacement return value " + value);
                    System.out.println("PASS " + methodName + "()=" + value.intValue()
                            + " enhanced=" + enhanced + " strict_aot=" + aot);
                    tested = true;
                }
                events.resume();
            }
            require(tested, "No breakpoint reached before timeout");
        } finally {
            if (vm != null) {
                try { vm.dispose(); } catch (VMDisconnectedException ignored) { }
            }
            process.getOutputStream().close();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            reader.join(2000);
        }
    }

    private static void runTraining(List<String> command) throws Exception {
        System.out.println("TRAIN " + String.join(" ", command));
        Process training = new ProcessBuilder(command).inheritIO().start();
        try {
            if (!training.waitFor(60, TimeUnit.SECONDS)) {
                throw new AssertionError("AOT training timed out");
            }
            require(training.exitValue() == 0, "AOT training exited " + training.exitValue());
        } finally {
            if (training.isAlive()) training.destroyForcibly();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
