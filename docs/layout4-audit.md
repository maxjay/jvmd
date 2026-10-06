# LAYOUT 4 implementation audit

This is Appendix A of [the Stage 3 specification snapshot, revision 123](jvmd-stage3-bodies.md), before processor support (Appendix F) and body attribution. It creates no Stage 3 proof or result. The remaining Stage 3 gates are tracked in [stage3-progress.md](stage3-progress.md).

## Projection and sum semantics

| Projection | Entry identity and meaning | Counterexample checks |
| --- | --- | --- |
| T; r; member ranges | `Digest(m || res)`, summed over a map of declaration keys to resolution facts | Exchanging two constant values between unchanged field keys preserves the independently computed old sum of Digest(res), but changes the keyed x range, r and oSum. Throws-list and two-owner member-set exchanges are also covered. Insertion/removal/replacement conservation is checked by actual tree edits. Reordering input declarations preserves the sorted map. |
| N | One entry per fact: exact simple name, kind, TYPE outer-or-empty, and m; identity h unchanged | sum(N) = r. Exact member-type prefixes exclude Foobar, direct Foo$Bar and deeper Foo.Bar when looking for a different direct member. Class/source/stub name parity is exercised. |
| O; oSum | Each owner entry sums that type's key-bound T identities | The change in r equals the sum of changed oSum values; each changed oSum equals the sum of its changed member ranges. |
| A | `Digest(m || tail)`, summed over nonempty annotation projections | An annotation-value change and a parameter-name change move A while T/L/stubs stay equal. Empty tails contribute no entry. A edits obey the same conservation equation as T. |
| E and EA | `Digest(edgeKey)` for a set of relations, split by edge kind | Repeated observations deduplicate; order does not matter. Annotation edges are produced by decoding the same retained tail used for A. An annotation-value edit preserves EA while changing A. |
| DD, DS, DC | `Digest(typeKey || winning oSum)` | API changes move the identity; a storage location is not a dependency. Existing route-conflict/order tests cover winner changes, duplicate leaves and commutative disjoint folds. Conservation is also tested on a definer edit. |
| Bound R | Sum of leaf r values; no new a sum | Order is represented by the route content list. A-only edits keep k, routeHash and the definer projections unchanged. SL persists the own (k,a) binding, and AL persists both full annotation roots; neither introduces a route sum. Both k and a travel on bindings. |

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
- Res.Type carries the exact innerName from InnerClasses or the completed source element. Res codecs carry only the three warning bytes (Deprecated attribute, presence/forRemoval, SafeVarargs); since is excluded. Convenience constructors that silently supply no warnings are removed; the module field gains its own presence marker before those bytes. LAYOUT 4 invalidates earlier generations.
- SourceFacts uses javac's `TypeCompound` positions directly. Index compilation therefore exports javac code/util packages to `dev.jvmd.index`; the existing classpath test/runtime launch configuration already exports those packages to `ALL-UNNAMED`.
- Identical-option parity required correcting javac parameter-table behavior: private inner outer-instance parameters are synthetic; other member-inner ones are mandated; their generated names depend on nesting depth. Mandatory flags may be emitted without `-parameters`, and canonical record constructors retain names without that option.
- The Maven test model now reads effective source roots and compiler configuration, including build-helper roots. Exports to named compilation targets are mapped to `ALL-UNNAMED` for the accepted Stage 2 classpath-mode boundary. This change repairs the test inputs; it is not a new production Maven model provider.
- Existing ClassFacts ownership is retained, including how record annotations appear on generated declarations. Extra parameter-annotation and record-component tail formats are not introduced.

## Validation

Commands and final measurement results are recorded in stage3-progress.md after the gate completes. The warm package remains untouched.


## Review correction evidence

The new persistence records are recoverable without Stage2.Result or Built. SL is in the committed LOCAL tree; AL is keyed by the digest of the two annotation root hashes and stores their hashes, sums, counts and levels. A source module with no dependents and two jars sharing k but differing in annotations cover the previously unreachable cases. Rocks reopening checks the same records.

Package-valued qualified-name heads now use the same candidate universe as on-demand type answers, with no answered package omitted, and never visit package declarations or bodies. Form-1 member-type absences consume only the N prefix (name, TYPE, direct outer), with ordinary ContentTree prefix handling. No binary-name byte bounds remain.

The controlled Stage 1 comparison, including exact inventory/JDK identities and per-tree build counts, is in [layout4-review-measurements.md](layout4-review-measurements.md). The earlier Stage 2 timing was not evidence for the requested Stage 1 comparison.

Revision 123 says an old unbound sum would preserve each individual x/y range in its introductory counterexample; that sentence is mathematically inaccurate. The executable counterexample asserts equality of the whole unbound sum, as invariant 25 and the review request specify. It does not claim equality of an individual old one-entry range. The 2026-10-06 review amendment repairs the separate T/N inconsistency throughout the authoritative document and its snapshot: form-1 reads N, independently of the enclosing type's oSum once global shortcuts fail.

### Second full-diff review

The ST key now binds the exact member projection emitted in the outer stub: sorted `zstr internalName || opt<str> innerName || u16 innerFlags`. It still excludes member oSum. A class-file transformation holds the member's binary name/flags and every outer resolution fact fixed while changing innerName; the uncached outer bytes change, its ST key changes, and a shared cache returns the new bytes. Null versus present names and unsigned-byte ordering are checked separately. Existing member-method edits still leave the outer key unchanged.

Each package-valued MemberSelect in a header now records an exact form-0 absence; imports walk their package prefixes too. Tests cover q.r and q.r.s becoming types in either the own module or the route, qualified references and on-demand imports, with unrelated body-only users retaining valid header proofs. This follows recursive package/type classification in [JLS 6.5.2](https://docs.oracle.com/javase/specs/jls/se25/html/jls-6.html#jls-6.5.2) and [6.5.4.2](https://docs.oracle.com/javase/specs/jls/se25/html/jls-6.html#jls-6.5.4.2); no package-wide identity is added.

The source/class annotation fixtures now independently build N, verify each root against its stored nodes, and compare Diff, hash and sum alongside k/A/EA/a. The adversarial dollar-name case therefore pins the changed by-name projection directly. A separate assertion shows Base.Foo insertion changes its N absence with oSum(Base) equal. The specification now dispatches Arrange/Valid by form and names the generic reverse range index X|G with form in the key; LIVE's N delta path is explicitly independent of its owner O delta. No Stage 3 production record is introduced in this correction.


### Stage 2 C.1 classpath-mode amendment

For the accepted classpath-mode header compile, rewrite each javac `--add-exports module/package=target-list` target to `ALL-UNNAMED`, preserving module/package. Both `--add-exports value` and `--add-exports=value` spellings follow this rule. Sources and sibling stubs are in the unnamed module for this task, so named output-module targets cannot describe that compilation. Module-info remains parsed separately; this rule does not claim JPMS compilation support.

### Third mathematical audit: generation isolation and qualified member types

The current representation is `layout=4;parser=3` and `local=2`. A real RocksDB boot regression commits an opaque parser-2 ROOT and a marker record, then requires a new cold MACHINE boot in a distinct directory and verifies that both old records remain unchanged. Another replaces a current LOCAL root's format with local=1 and requires a cold rebuild before the current-format skip is allowed. No old fact codec is read or migrated.

Qualified member-type lookup records expected-zero N ranges for each owner before a declaration on each inheritance branch. The regression covers `Sub -> Mid -> Base`, with Base.Inner as the winner; additions in Sub or Mid invalidate qualified references and single/static-on-demand imports in both own-module and route-provider cases, while the containing owners' oSums remain equal. Executable bodies remain excluded. Lookup stops at the winner on that branch, preserving a proof when only a hidden ancestor gains Inner; a competing interface branch still contributes absences. An ancestor reached through that second branch can cause ambiguity even when the winner hides it on the first. This is checked against javac's `Resolve.findMemberType` / `findInheritedMemberType` and [JLS 8.5](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.5), following [qualified type lookup](https://docs.oracle.com/javase/specs/jls/se25/html/jls-6.html#jls-6.5.5.2). A collector-local cache shares repeated hierarchy and named-member traversals without adding a persisted identity.

The audit's Stage 2 reconciliation remains required before PR B. The current positive header codec still stores containing-type oSum, and the current reverse records still store consumer lists and omit absences. Those are not solved by the N repair above. Replace positive proofs with actual T ranges, including the headers of lookup-path types (changing Mid's superclass must invalidate a lookup through Sub), and replace reverse records with one empty key per dependency/project/path, including expected-zero N and D reads. Tests must derive candidates from tree deltas and prefix reads, with no file-row scan and no collapse of identical-byte files.

Definer state now uses a boot-local persistent balanced map. A cold fold builds it once from its sorted keys; a derived fold collects only changed types, point-reads their previous lists, and copies only their search paths. Unchanged entries and their branches are shared with the base. Per-node multiple-definer counts prune singleton branches during external conflict enumeration, replacing the copied conflict-name set too. Point lookup, containsKey and getOrDefault all stay logarithmic, including misses. DD/DS/DC still use the existing ContentTree codecs and semantic sums; the working map introduces no stored identity. The 32,768-type regression pins structural sharing and old-snapshot immutability, opens only changed leaves, and compares derived disjoint/conflict roots with fresh folds. A 3,000-update branching history exercises growth, deletion, conflict changes and old snapshots under both digests.

Leaf-set distance now counts with two indices and no allocated difference lists. Nearest-state selection still scans previous states; route ancestry must replace exhaustive selection before claiming project-size-independent routing costs. The same reconciliation will preserve full special-annotation metadata in A while retaining only the minimal warning projection in res, so a Deprecated.since edit changes A/a alone. Those are explicit outstanding changes, not claims about this head. Do not add RA, nSum, broad processor fingerprints, AL existence reads or node-store dedup reads.
