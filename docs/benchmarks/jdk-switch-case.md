# Project JDK switch

This case measures a change to the project platform while the server process,
source bytes, document version and source/class-file language levels remain
unchanged. It does not measure changing the server's own launch JDK.

| Stage | Required evidence |
|---|---|
| Preparation | Two canonical full JDK homes, release versions and hashes of all regular distribution files; identical probe source hash |
| Independent oracle | JDK 17 javac rejects exactly List.getFirst; newer javac accepts the same bytes; compiled main returns the first item |
| Baseline setup | Explicit update to JDK 17; project settings report that home, source/compliance/target 17 and release emulation disabled |
| Baseline API | First, warmup and steady completion at values.g include get and exclude getFirst; empty replies fail |
| Change acknowledgement | One updateJdk to the newer home; success must be true and the message must identify that home |
| Immediate API | The very next request is completion at the identical position; get and getFirst are required |
| Settled API | The normal bounded retry policy preserves every failed attempt, then requires a second correct response |
| Environment confirmation | Project VM reports the newer home, with unchanged language settings and both VMs still registered |
| Preservation | Every source byte, file membership and open document version remain unchanged; no workspace edit is accepted |

The transition starts at the updateJdk request send. Its acknowledgement is a
separate measured operation. Completion immediately after acknowledgement is
the first possible ordered semantic observation under this protocol. No
metadata polling or compiler execution occurs between those two requests.
Baseline setup and prelaunch independent compiler validation are explicitly
outside this interval; OS cache state remains uncontrolled.

The old and new compilers use `-proc:none -source 17 -target 17`, without
`--release`. Using `--release 17` for the newer compiler would deliberately hide
the very platform API difference under test. Compiler stdout, stderr, status,
signal, errors, commands and monotonic intervals are retained in preparation
evidence. Inherited Java option and classpath variables are removed from the
independent compiler environment. A random compiler failure, wrong runtime
output, a JRE without javac, a same-version pair or a missing home cannot pass.

Example from the repository root, with explicit existing tool paths:

```sh
node benchmarks/lsp-scenarios/run.ts \
  --servers jvmd,jdtls --profile pipe --pipe-build /path/to/build.json \
  --java-home /path/to/jdk-25 --alternate-java-home /path/to/jdk-17 \
  --jdtls-home /path/to/jdtls --only PRJ-02/jdk-switch \
  --blocks 1 --warmup 1 --samples 2 --timeout-ms 30000 \
  --output /new/path/to/jdk-switch-pilot
```

This example is a development pilot. Pipe transport, one block and uncontrolled
OS caches cannot establish product lifecycle or comparative performance. A
server may be classified unsupported only from the recorded command capability;
missing benchmark tools are a harness failure. Implementation coverage does not
mean a supported server passed this case.
