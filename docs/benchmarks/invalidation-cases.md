# Independent invalidation controls

Run the additional cases with `--invalidation true`. The workbook remains 175
cases and 107 required variants; these seven controls add no workbook coverage.
Use the same server, JDK, profile and output arguments as the ordinary suite.

| Case | Question and mutation | Required witness |
|---|---|---|
| `INV/body-only` | Does a provider body edit preserve the caller's declaration? | Exact unchanged overload URI and range. |
| `INV/overload-add` | Does adding a more-specific String overload change an unchanged caller? | Definition selects the new overload, not the old same-name Object overload. |
| `INV/namespace-add` | Does a new same-package type shadow the wildcard-imported type? | Unchanged caller's field changes from String to int. |
| `INV/namespace-remove` | Does deleting that type expose the imported type again? | Unchanged caller's field changes from int to String. |
| `INV/namespace-unrelated` | Does an unrelated name preserve the existing lookup? | Imported String field remains correct. |
| `INV/classpath-reorder` | Does ordered duplicate-class lookup follow a changed classpath order? | Replacement member appears and the old member disappears; every archive byte is unchanged. |
| `INV/classpath-identical` | Does an identical metadata notification preserve lookup? | Original member remains and the competing member is absent. |

Every case restores its own input. First-use, warmup, repeat, first post-mutation
response, convergence attempts and a second correct response are retained. A stale
first answer remains failed even if later probes converge. Body-only and unrelated
controls establish semantic preservation, not proof that the mutation was processed
or that no internal work occurred. Internal work remains unavailable unless its
owner, epoch and complete scope are separately established.

Source cases compile their changed source in the independent compiler oracle.
Binary cases compile and package two deterministic same-class archives before
measurement. Maven and Eclipse metadata declare the same order. Reordering changes
only those two declarations; archive inventories are checked after the probes.
These complement LIFE-04/05 live retention, LIFE-06 disposal, LIFE-02 persisted
restart, and LIFE-07/08 shared machine dependency visibility. Together they test
observed semantic ownership boundaries; retained IDs alone never establish reuse.
