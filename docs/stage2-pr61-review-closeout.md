# PR #61 scheduler and persisted-state review

The review of `66d27412dcb7ce559acda2e3f0a6e3a1352aad9b` identified a real missed-consumer bug and repeated work in the stored definer/reverse indexes. This follow-up addresses all seven findings. It does not claim completion of Stage 3's byte/diagnostic/read-set oracle.

| Review finding | Change | Regression/evidence |
| --- | --- | --- |
| DC winner changes missed T/N consumers | Separate definer delta; type-wide T/N prefixes; exact `Diff.content` includes equal-h routing changes | `HeaderReverseTest.routePermutationReachesPositiveAndMemberTypeConsumersWithoutAnyLeafEdit` changes only route order, preserves both provider leaves, reaches both consumers and invalidates their exact proofs |
| Historical raw X controlled lookup cost | Current `X\|H5\|` inserts/deletes commit atomically with LROOT; old empty values remain in historical LOCAL nodes | `CurrentReverseTest`: 12 rounds × 500 retired consumers, one current prefix hit and zero point/LOCAL reads after each round, in memory and Rocks including reopen |
| N/D over-fan-out | N and effective-presence deltas use key membership; same-key value replacements are ignored | N/O h replacements return no absence consumers; real insertion/removal reaches the named paths; loser-only DC reorder is also ignored |
| Stored DD/DS discarded fold mathematics | `DF\|leafSet` stores all-definer, multiple-definer and disjoint ContentTree roots; exact restore is lazy | 32,768-type fixture restores with zero reads, derives by opening only the old/new small O leaves, and matches fresh canonical roots |
| Exact DC rebuilt each boot | Read `DC\|routeHash` before constructing; one future per exact route | Repeated cold boot and another checkout have zero conflict builds/node emissions and zero fold O opens |
| New DC rebuilt in full | Ordered route Diff identifies changed/moved leaves; their O keys drive `apply(parentDC)` | Moves, additions, removals and duplicate leaves match fresh conflict construction; an unchanged 32,768-type leaf is never opened |
| Flat leaf sets required full comparisons | Canonical leaf-set ContentTree, `k → empty, h=H(k)`; state retains its root | A one-leaf replacement among 16,384 leaves compares at most 512 leaf-set entries; order/repeat-only changes compare zero |

DC's semantic h excludes the provider's storage identity. A first-provider swap can therefore preserve the owner's oSum while changing its selected N member-type range. `Diff.trees` remains the semantic key/h operation; the new `Diff.content` supplies the exact key/value/h delta needed for routing. Neither adds a broad dependency to the proof.

Parallel leaf-set derivation exposed a node-publication race: global deduplication could suppress a node still buffered by another worker. Stage 2 now drains shared pending nodes before publishing a readable root. Stage 1 retains its streaming sink and passes its existing first-node-before-last-class regression. A controlled two-thread test verifies that flushing a duplicate publishes the original worker's node with one physical node write.

LOCAL advances to 5; MACHINE remains layout 4/parser 4 on this PR. Earlier raw header prefixes cannot enter a current query. The later Stage 3 branch has independent parser/LOCAL advances which must be preserved when integrating this change. The Downloads Stage 2 authority retains its later parser-5 Java String amendment while receiving these scheduler corrections.

Binding still visits the model's route entries and constructs canonical sets. Initial uncached definer/conflict states still pay initial construction. Costs for derived states are relative to the explicitly chosen route parent. Warm boot, garbage collection and the Stage 3 driver remain separate work.

Validation reports are in `docs/measurements/stage2-review61-*`. They include both digest implementations, source/class parity for all 26 scopes, direct cold-reuse counters, and the frozen-source test selection.

The final frozen gate passed **292 tests**, no failures/errors/skips, in 3m00s. All 501 Java files remained byte-identical throughout. SHA-256 and SHA3-256 each reported 26/26 exact source/class k matches, no annotation differences and no faults. Each repeated cold boot hit 22 DF and 22 DC records, with zero fold O opens, leaf-set comparisons, conflict builds/applies/O opens/touched entries/node emissions.
