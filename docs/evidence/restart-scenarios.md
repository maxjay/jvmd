## Strict task A1–A6 at 5,000 units

Regenerate: `mvn -pl jvmd-tests test -Dtest=RestartScenarioTest -Djvmd.scenario.units=5000 -DexcludedGroups=corpus`
(run at `513639c`, 4 CPUs). Every count is an exact assertion in the test; a passing run is the evidence.

| Test | Fixture (seed `SyntheticProjects.SEED`) | Asserted |
|---|---|---|
| `a1NoChangeRestartRestoresEveryUnitWithoutJavac` | random DAG + hub + layered (1,667 + 1,667 + 1,666 units) | javac 0, restores 5,000 |
| `aRequestWaitsOnlyForItsDependencyCone` | layered | a request restores only its dependency cone |
| `a2HubBodyEditRecompilesOnlyTheHub` | hub | javac 1, restores 4,999 |
| `a2InReverseOrderEarlyCutoffAttributesTheHubOnceForItsFirstDependant` | hub | the hub is attributed once, by early cutoff |
| `a3PrivateMethodAddedToTheHubRecompilesItsDirectCompleters` | hub | javac 1 + direct completers; transitive dependants restored |
| `a4PublicMethodInAMidLayerStopsAtDependantsWhoseProjectionIsUnchanged` | layered | javac 1 + direct dependants, counted per layer |
| `a5NewTopLevelTypeRecompilesOnlyUnitsThatConsultedItsPackage` | random DAG | javac only for units holding the package or a matching negative |
| `a6BodyEditInsideAThreeCycleRecompilesTheCycleOnly` | random DAG + 3-cycle | javac 3 |

Result: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1347 s`. A1 was then made to hold exactly
5,000 units (it held 4,998, and the layered generator dropped a remainder) and rerun alone:
`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 162.5 s`.
