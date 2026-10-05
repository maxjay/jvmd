# LAYOUT 4 implementation audit

This is Appendix A of [the Stage 3 specification](jvmd-stage3-bodies.md), before processor support (Appendix F) and body attribution. It creates no Stage 3 proof or result. The remaining Stage 3 gates are tracked in [stage3-progress.md](stage3-progress.md).

## Projection and sum semantics

| Projection | Entry identity and meaning | Counterexample checks |
| --- | --- | --- |
| T; r; member ranges | `Digest(m || res)`, summed over a map of declaration keys to resolution facts | Exchanging two constant values between unchanged field keys changes r and oSum. Insertion/removal/replacement conservation is checked by actual tree edits. Reordering input declarations preserves the sorted map. |
| O; oSum | Each owner entry sums that type's key-bound T identities | The change in r equals the sum of changed oSum values; each changed oSum equals the sum of its changed member ranges. |
| A | `Digest(m || tail)`, summed over nonempty annotation projections | An annotation-value change and a parameter-name change move A while T/L/stubs stay equal. Empty tails contribute no entry. A edits obey the same conservation equation as T. |
| E and EA | `Digest(edgeKey)` for a set of relations, split by edge kind | Repeated observations deduplicate; order does not matter. Annotation edges are produced by decoding the same retained tail used for A. An annotation-value edit preserves EA while changing A. |
| DD, DS, DC | `Digest(typeKey || winning oSum)` | API changes move the identity; a storage location is not a dependency. Existing route-conflict/order tests cover winner changes, duplicate leaves and commutative disjoint folds. Conservation is also tested on a definer edit. |
| Bound R | Sum of leaf r values; no new a sum | Order is represented by the route content list. A-only edits keep k, routeHash and the definer projections unchanged. Both k and a travel on bindings. |

The algebra is modulo the prime for the digest width. Sums are compared and updated; they are never storage keys. `k = root(T).hash`; `a = Digest(root(A).hash || root(EA).hash)`. Leaf L and stubs depend only on the resolution projection.

`ContentTreeEditTest` independently calculates before/after map deltas for randomized removals, insertions, replacements, duplicate edits and absent removals, then verifies every stored hash and sum. `Layout4Test` additionally checks real T, A, O, EA and definer edits, owner/range conservation, field-value permutations, and the number of new nodes for one annotation-value edit in a 2,048-method class.

## Computation inputs and observations

| Computation | Actual inputs | Evidence |
| --- | --- | --- |
| Binary facts | Class-file declarations, constants, signatures, warning metadata, retained declaration/type annotations, method parameter metadata | Independent javac fixture compilation followed by ClassFacts; a separate class-file transform removes tail metadata and leaves k and L unchanged. Warning/meta-annotations that contribute to res survive the transform. |
| Source facts | Completed declaration symbols after enter/member completion; compiler parameter option; already assigned signature type-annotation positions | Exact k, A hash/sum, EA hash/sum and a comparisons against independently compiled class files, with tree Diff diagnostics. Cases cover class/method bounds, arrays at multiple dimensions, receiver/return/formal/throws targets, nested generic/wildcard paths, record propagation, inner constructors and all retention kinds. Both parameter settings are exercised. Broken method bodies leave the projections equal, and the test checks that their expression types remain unattributed. |
| Annotation edges | Encoded tail only | Both producers call `Ann.tailAnnotationTypes`; there is no second annotation scanner. Source retention stays outside A/EA. No second annotation representation is introduced. |
| Stubs | T declarations and O identities; existing stub cache | An independently instrumented node reader checks every read belongs to T/O and excludes A/EA nodes. Compilation against real classes and stubs compares errors and warnings. |
| Definer fold | O of added/removed leaves | Instrumented node reader accepts only O nodes. Existing fold tests compare incremental and full construction, including insertions/removals and conflicts. |
| Bind | Route entries and resolved leaf records | Annotation-only substitutions preserve route identities and definer trees while changing the parallel a sequence. |
| Header proof | Positive symbols plus package/member-type lookup absences; sealed own leaf preceding route | Mutations cover own-package shadowing in either own or route indexes, inherited member types, explicit imports, package versus wildcard priority, ordinary/static on-demand member imports, and unrelated body edits. Only expected-zero observations are retained. |
| Persistence/deduplication | Existing MACHINE/shared records; new records of this boot | Store instrumentation checks no project-local read before root publication, one sync before root, and zero L/S/ST writes when a source leaf reuses a jar's k and cached stubs. |

The full two-sided javac loaded-class/absent-lookup oracle in invariant 30 belongs to `Attribute(f)` and is still required in the Stage 3 PR. The header mutation and storage-read checks above are the evidence available for this amendment; they do not claim that body read-set oracle is implemented.

## Attribute input checklist boundary

Appendix A establishes the T/O/definer projections, warning-preserving stubs, own-first header resolution and annotation identities. It does not establish ACI, body proof descent, result storage or context pooling. Source bytes/name/options, full javac runtime/locale, JDK/route/own inputs, processor code/configuration/origins, Filer reads, aggregator domains and body results must each be checked against the implementation when Appendix F and Stage 3 are added. No scope-wide identity is introduced here as a substitute for those proofs.

## Implementation notes

- `LeafBuilder.seal()` retains its existing k return type and exposes a and both annotation roots after sealing. A/EA nodes are written before API-leaf deduplication, so sharing L does not drop distinct annotation projections.
- Res codecs gain explicit warning metadata (including `forRemoval`); the module field gains its own presence marker before those bytes. LAYOUT 4 invalidates earlier generations.
- SourceFacts uses javac's `TypeCompound` positions directly. Index compilation therefore exports javac code/util packages to `dev.jvmd.index`; the existing classpath test/runtime launch configuration already exports those packages to `ALL-UNNAMED`.
- Identical-option parity required correcting javac parameter-table behavior: private inner outer-instance parameters are synthetic; other member-inner ones are mandated; their generated names depend on nesting depth. Mandatory flags may be emitted without `-parameters`, and canonical record constructors retain names without that option.
- The Maven test model now reads effective source roots and compiler configuration, including build-helper roots. Exports to named compilation targets are mapped to `ALL-UNNAMED` for the accepted Stage 2 classpath-mode boundary. This change repairs the test inputs; it is not a new production Maven model provider.
- Existing ClassFacts ownership is retained, including how record annotations appear on generated declarations. Extra parameter-annotation and record-component tail formats are not introduced.

## Validation

Commands and final measurement results are recorded in stage3-progress.md after the gate completes. The warm package remains untouched.
