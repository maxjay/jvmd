# F05 indexed resolution validation

The grouped C proof remains an audit/reference representation. Production attribution now also materialises a query ContentTree and a compact CI receipt. The cold driver selects CI with the unit's other records under BROOT. ProofIndex.advance validates this persisted representation without decoding C, enumerating its type groups/absences, or recomputing ACI over all entries. This does not implement a production LIVE scheduler or complete F04's semantic read model.

## Stored state and identity

    CI|project|module|scope|path
        version
        validated binding: own k, route hash, DD/DS/DC root hashes
        inputs: basename, source content identity, options identity, compiler build
        query-tree root
        already computed ACI
        optional processor context and actual observations

    query tree:
        key = form || type || kind || name
        value = expected range sum (D absence expects zero)
        h = Digest(key || value)

T, N and D keep distinct namespaces. The query-tree Merkle root addresses the exact stored query map. It is not added to ACI. The binding roots are validation coordinates, also excluded from ACI. Cold capture computes the existing ACI from the actual query answers and fixed inputs. Successful validation retains that ACI and query root; advancing the receipt only changes its validated binding. Source, basename, options or compiler changes reject reuse before resolution checks.

A processed receipt still requires independently current processor observations and the existing capability-history checks. Those observations can have their own size-dependent work. The 320-byte unprocessed receipt measurement is not a constant-size claim for arbitrary processor models.

## Deriving a sufficient frontier

A Transition is derived internally from the exact old/new stored binding roots. The API does not accept an unchecked list of changes which could accidentally omit a domain.

1. Diff own T and N independently.
2. Diff the ordered route ContentLists. When every removed/added element occupies the same position, compare each substituted leaf's T and N directly. This avoids expanding every DD value merely because its containing leaf k changed.
3. For other ordered edits, visit O for the moved/added/removed leaves to find owners whose winner could change.
4. Resolve affected owners against both own-first bindings. Compare each distinct effective provider pair's T/N trees once, memoising the pair delta. Keep the relevant owner's queries.
5. Compare old/current answers to remove shadowed changes and unchanged projections before probing any consumer proof. T changes select exact kind/name and whole-kind questions; N uses its independent member-type sum; D depends only on effective presence.
6. If a provider disappears, visit that owner's T/N proof prefixes too. Expected-zero member questions have no fact key in the delta but are no longer valid without their owner.

The provider-disappearance rule is necessary: enumerating only changed fact names misses a proof that contains solely N(Base, Missing)=0 or T(Base, METHOD, missing)=0 when Base itself disappears. Tests include these cases independently of any positive Base header proof.

An arbitrary route permutation is not treated as a same-position substitution. Duplicate leaves and mixed insertion/removal/reordering retain the conservative owner path. Exact answer comparison still determines the final query frontier.

## Intersection and receipt chaining

For each changed question, the validator performs one exact query-tree get. Unmatched questions do not decode proof values. Matching expected answers are compared to current answers computed once per shared transition. A removed provider uses its two owner prefixes. The receipt must name exactly the transition's starting binding; a mismatched or empty transition from another state throws instead of silently validating it.

A successful receipt can be encoded and reopened at the new binding. Alternatively an unchanged selected unit may retain its older receipt: the next transition must then start at that older binding. The test reuse harness memoises transitions by stored starting binding; it does not pretend all retained files were re-attributed at the last project state.

BodyValidation now prepares the accepted BROOT binding to current LOCAL binding transition, joins its exact changed questions with current X, and deduplicates SourceUnits before opening CI. Exact-question seeks include project/module/scope; disappeared-provider seeks use the shorter T/N owner prefixes, then filter the returned current consumers. This exceptional prefix can visit consumers of the owner in other projects: that fan-out is counted, not claimed to be scope-local constant work.

Only selected receipts are decoded. Their actual original bindings anchor validation to the current target, with one prepared transition per distinct starting binding. Retained receipts are not rewritten. LROOT/BROOT and their publication sequences are checked around discovery/validation, detecting concurrent publication including ABA; the existing BodyGeneration commit also checks roots. Historical CI resolves through its BROOT entry and immutable BV value, never the mutable raw CI binding.

The caller must supply the complete non-T/N/D candidate set: source/basename/options/compiler changes, generated and processor/configuration changes, and previously non-reusable units. The primitive does not infer those inputs or discharge F04. Its correctness contract starts from an accepted baseline with every omitted unit previously valid; an arbitrary partial BROOT is not such a baseline. A missing selected CI requires attribution. Initial/missing/incompatible scope baselines require the cold path. A future production planner must maintain these additional candidate domains without an all-file scan.

The 2,000-file harness now supplies its explicit mutation/configuration events and uses this current-X join, rather than enumerating receipts. Source snapshots and native/reporting inventories remain full fixture operations outside validation. Its original F04 initial-admission assertion remains unchanged and failing, so the Lombok edit loop is not claimed as a passing end-to-end measurement. A separate native fixture exercises the actual selective pipeline for supported unprocessed inputs.

**Derived proposition (T/N/D join).** Let X(q,u) mean the currently selected unit u observes q, and let ΔQ contain exactly the questions whose effective answers differ between accepted S and target S'. Then affected units are the distinct u in ΔQ join X, plus consumers under removed-provider T/N prefixes and the caller's other-input candidates. If u is absent from that set, all its T/N/D observations remain equal to those validated at S. This composes inductively across accepted publications even when its stored CI still names an older S0. If u is selected later, check S0→S' from that receipt; the latest step alone is not a valid receipt transition. This is ordinary incremental join algebra; it does not establish F04's missing metadata domain or invent a compiler-locality theorem from database results.

## Work contract

Cold capture is O(P) query entries plus the existing canonical-tree build, ordering and encoding costs. It writes the query tree once through the generation's shared node sink. The flat audit proof is still persisted at capture time.

For a prepared transition with d changed questions, intersection costs d indexed lookups, plus proof entries under genuinely removed-provider prefixes. No term scans all unrelated proof entries or type absences. Actual tree height, CAP-bounded node entries, key lengths and encoded node bytes still matter.

Preparation is shared across consumers of the same transition, not hidden inside each file. It includes own/route Diff, T/N differences for changed provider pairs, owner enumeration for non-substitution route edits, and old/current answer reads. Adding/removing a whole leaf or reordering a large conflicting leaf can require work proportional to its affected owners. The current CAP adversary can also make Diff linear; this repair does not resolve F09 or assert unconditional logarithmic tree height/locality.

Counters distinguish preparation record/node reads and bytes, proof-node reads and bytes, matched proof entries and leaf-pair comparisons. The work-axis allocation measurement includes current binding capture, persisted receipt decoding, frontier construction and intersection. Actual physical RocksDB/JNI/disk bytes are not inferred from logical node reads.

BodyValidation additionally counts binding/root setup reads and bytes, current-X prefix seeks/hits/key bytes, selected receipt count/bytes, direct candidates and prepared transitions. The unit/root point-lookup cost includes actual tree height; no unconditional constant bound for those node reads is claimed. Validation performs no writes. Retained allocation is not measured by the cumulative thread allocation counter; the plan retains affected questions/provider pairs and selected paths, not decoded unrelated receipts.

## Validation

The frozen candidate checkpoint at `2690ef5a` has **316 tests: 314 pass, two original Lombok admission failures, zero errors/skips**. Both digests match 1,415 native CFs and all 26 ordered diagnostic scopes, including 10 descriptors. The subsequent strict-agent overload-hook repair changes test instrumentation only; the real strict read run still fails coverage. [Exact manifests, commands and candidate/proof/byte/allocation rows](measurements/stage3-pr62-completion-candidates.txt). The candidate join is tested; full compiler-observation acceptance is not complete.

The completion-brief follow-up adds a current-consumer growth fixture (65/513/4,097 live files with 64/512/4,096 retired consumers), fan-out zero/one, real Rocks close/reopen with an unchanged older receipt, pure conflicting-provider permutation, expected-zero-only T/N owner disappearance, explicit source changes and root/sequence race rejection. The native pipeline starts from cold Stage3, uses current X and CI, attributes only selected units, applies OUT and changed-unit publication, and compares every output byte with independent native javac for unrelated constant, consumed constant and direct body edits. This is a passing supported-input primitive fixture, not restored Lombok admission or complete Stage3 acceptance. The initial focused gate has 24 passing tests; the full frozen follow-up is recorded separately when complete.

Hosted Tests at `5d12ba95` found one integration-test inventory omission: CI was not recognised by the Rocks checkpoint. `ff8554ff` repairs the inventory and additionally verifies reopened, BROOT-selected receipts, their query trees and selected RS references. RocksLocalBootTest, ProofIndexTest and Stage3Test pass together: 19 tests, zero failures/errors/skips. This test-only correction does not change the production receipt or tree format.

The focused integration gate passes 174 tests after adding value equality to the new receipt; native class/diagnostic assertions were retained. The subsequent frozen 598-source gate has 300 tests: 298 pass and the two unchanged Lombok admission cases fail. Both digests match 1,408 native class files and all 26 ordered diagnostic scopes (593 compilations, 10 descriptors). Exact commands, counters and limits are recorded in the measurement file.

The semantic matrix compares indexed validation with independent full grouped-proof validation for 24 states (different own providers and empty/single/conflicting/reordered/duplicate routes): 576 full-proof transitions per digest, plus expected-zero-only T and N controls. Native provider-order regressions exercise the equal-T/different-N case. Production cold tests read CI through historical BROOT, retain serial/parallel deterministic roots and validate native constant/unrelated-edit behaviour without reading C/RS/CF during validation.

The scaling fixture grows unrelated positive queries and type absences together from 257 to 2,049 to 16,385 entries, holding changed referenced questions at zero or one, with own and external-leaf edits under both digests. The unprocessed receipt stays 320 bytes; the index matches zero or one proof entry. Both frontier preparation and persisted decoding are included. Full counters and final gates belong in [the measurement record](measurements/stage3-pr62-indexed-proof.txt).
