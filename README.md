# jvmd

A resident Java semantic and runtime daemon. The implementation follows [revision 6](docs/design.md).
Check [PROGRESS.md](PROGRESS.md) for tested checkpoints; unimplemented phases are not advertised as capabilities.

Build with the pinned JDK 25.0.4.1+1 and Maven 3:

```sh
mvn -B -DskipTests install
bash jvmd-dist/assemble.sh
bash jvmd-dist/train-aot.sh
mvn -pl jvmd-tests test -Dgroups=phase-1 -DexcludedGroups=corpus
jvmd-dist/target/image/bin/jvmd
```

The last test command includes performance assertions and requires a Linux environment that permits
Unix domain sockets. GitHub Actions builds the runtime image, trains its cache, and runs that gate.
The daemon reads `~/.config/jvmd/config.json`; protocol messages use Content-Length framing over
`$XDG_RUNTIME_DIR/jvmd-<uid>.sock`. Without XDG runtime state, a private directory under `/tmp` is used.

Never put application dependencies on the daemon classpath. Debuggee AOT is disabled by default.
Use `JVMD_JDK_HOME` and `JVMD_JBR_HOME` to run the standalone `jvmd-tests/smoke` scripts.
