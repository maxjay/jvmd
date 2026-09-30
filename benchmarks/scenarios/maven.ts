import assert from "node:assert/strict";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {pomXml,installArtifact} from "../harness/maven.ts";
import {position,completionOracle} from "../harness/oracles.ts";
import {dependencyFixture,dependencyTarget,dependencySource,library,DEPENDENCY_USE} from "../harness/dependencies.ts";

/**
 * The project model is Maven's: these cases change pom.xml on disk, as a developer would,
 * and expect the next answers to follow without any server-specific command.
 */
const at=(c:ScenarioContext,token:string,shift=0)=>({textDocument:{uri:c.file("DependencyUse.java").uri},position:position(c.text("DependencyUse.java"),c.text("DependencyUse.java").indexOf(token)+shift)});
const RELEASE_PROBE="package bench;\nimport java.util.List;\npublic class ReleaseProbe { public String first(List<String> values) { return values.get(0); } }\n";

export const mavenCases:CaseDefinition[]=[
  {id:"ENV-01/dependency-add",family:"ENV-01",apis:["API-050"],capability:"definitionProvider",fixture:{"DependencyUse.java":DEPENDENCY_USE},
    variant:"declare a new dependency in pom.xml; its type resolves at the unchanged caller",
    run:async c=>{
      await c.open("DependencyUse.java");
      await c.query("textDocument/definition",at(c,"original()",1),v=>assert.deepEqual(Array.isArray(v)?v:v?[v]:[],[],"undeclared dependency must not resolve"),"baseline");
      // As when Maven downloads a new dependency: the artifact reaches the repository while the server runs.
      installArtifact(c.fixture.repository,c.javaHome,library(c.fixture),{"dep/Library.java":dependencySource()});
      const trigger=c.writeProjectFile("pom.xml",pomXml({...c.fixture.pom,dependencies:[library(c.fixture)]}));c.fixture.pom.dependencies=[library(c.fixture)];
      await c.transition("textDocument/definition",()=>at(c,"original()",1),v=>dependencyTarget(v,c.fixture),trigger,"dependency declared in pom.xml resolves to its -sources.jar");
      c.compileOracle();
    }},
  {id:"ENV-01/dependency-swap",family:"ENV-01",apis:["API-044"],capability:"completionProvider",fixture:{"DependencyUse.java":DEPENDENCY_USE},prepare:dependencyFixture(true),
    variant:"replace library A with library B in pom.xml; completion offers B's member, not A's",
    run:async c=>{
      await c.open("DependencyUse.java");const params=()=>at(c,"original()");
      await c.query("textDocument/completion",params(),v=>completionOracle(v,["original"],["next"]),"baseline");
      const b=library(c.fixture,"B"),trigger=c.writeProjectFile("pom.xml",pomXml({...c.fixture.pom,dependencies:[b]}));c.fixture.pom.dependencies=[b];
      await c.transition("textDocument/completion",params,v=>completionOracle(v,["next"],["original"]),trigger,"completion follows the dependency declared in pom.xml");
      c.change("DependencyUse.java",c.text("DependencyUse.java").replace("original()","next()"));c.compileOracle();
    }},
  {id:"PRJ-02/release-change",family:"PRJ-02",apis:["API-044"],capability:"completionProvider",fixture:{"ReleaseProbe.java":RELEASE_PROBE},
    variant:"raise maven.compiler.release from 17 to 21; List.getFirst appears only on the new platform",
    run:async c=>{
      await c.open("ReleaseProbe.java");
      const params=()=>({textDocument:{uri:c.file("ReleaseProbe.java").uri},position:position(c.text("ReleaseProbe.java"),c.text("ReleaseProbe.java").indexOf("values.get")+"values.".length)});
      await c.series("textDocument/completion",params(),v=>completionOracle(v,["get"],["getFirst"]));
      const trigger=c.writeProjectFile("pom.xml",pomXml({...c.fixture.pom,release:21}));c.fixture.pom.release=21;
      await c.transition("textDocument/completion",params,v=>completionOracle(v,["get","getFirst"]),trigger,"completion follows maven.compiler.release in pom.xml");
    }},
];
