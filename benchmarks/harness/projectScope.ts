import assert from "node:assert/strict";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {type Fixture,addSource,addDependency,TEST} from "./fixture.ts";
import {installArtifact,artifactJar,caseGroup,type Dependency} from "./maven.ts";

export const TEST_SOURCE="package bench; public class TestRootWitness { public int testOnly() { return new Customer().number(); } }\n";
/** Maven's test root holds a source that only belongs to the test classpath. */
export function prepareProjectRoots(fixture:Fixture){addSource(fixture,"TestRootWitness.java",path.join(TEST,"bench/TestRootWitness.java"),TEST_SOURCE);}

const scoped=(fixture:Fixture):Record<"main"|"test",Dependency>=>{
  const groupId=caseGroup(fixture);
  return {main:{groupId,artifactId:"main-only",version:"1"},test:{groupId,artifactId:"test-only",version:"1",scope:"test"}};
};
/** One compile-scope and one test-scope dependency: the runtime classpath holds only the first. */
export function prepareClasspathScopes(fixture:Fixture,javaHome:string){
  prepareProjectRoots(fixture);const deps=scoped(fixture);
  installArtifact(fixture.repository,javaHome,deps.main,{"scope/MainOnly.java":"package scope; public class MainOnly { public static int value() { return 31; } }\n"});
  installArtifact(fixture.repository,javaHome,deps.test,{"scope/TestOnly.java":"package scope; public class TestOnly { public static int value() { return 47; } }\n"});
  addDependency(fixture,deps.main);addDependency(fixture,deps.test);
}
export function classpathScopeOracle(value:any,fixture:Fixture,scope:string){
  const root=fixture.root,deps=scoped(fixture);
  assert.equal(path.resolve(fileURLToPath(value.projectRoot)),path.resolve(root));assert.deepEqual(value.modulepaths,[]);
  const expected=[path.join(root,"target/classes"),artifactJar(fixture.repository,deps.main),
    ...(scope==="test"?[path.join(root,"target/test-classes"),artifactJar(fixture.repository,deps.test)]:[])].sort();
  assert.deepEqual(value.classpaths.map((p:string)=>path.resolve(p)).sort(),expected,"classpath includes the wrong scope or omits an output or dependency");
}
