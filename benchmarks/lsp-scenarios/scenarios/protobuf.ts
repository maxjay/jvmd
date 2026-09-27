import assert from "node:assert/strict";
import path from "node:path";
import {readFileSync,writeFileSync} from "node:fs";
import {pathToFileURL,fileURLToPath} from "node:url";
import {type CaseDefinition,type ScenarioContext} from "../harness/ScenarioContext.ts";
import {inventory} from "../harness/fixture.ts";
import {prepareProtobuf,cleanupProtobuf,PROTO_USE,GENERATED_ROOTS} from "../harness/protobuf.ts";
import {position,range,exactLocations} from "../harness/oracles.ts";

const generated=(c:ScenarioContext)=>Object.fromEntries(GENERATED_ROOTS.flatMap(root=>Object.entries(inventory(path.join(c.fixture.root,root))).map(([file,digest])=>[root+"/"+file,digest])));
const probe=`package bench;
public class HarnessOracle {
    public static void main(String[] args) throws Exception {
        var value = bench.generated.Greeting.newBuilder().setMessage("hello").build();
        if (!bench.generated.Greeting.parseFrom(value.toByteArray()).getMessage().equals("hello")) throw new AssertionError("main protobuf round trip");
        var test = bench.generated.TestGreeting.newBuilder().setCode(42).build();
        if (bench.generated.TestGreeting.parseFrom(test.toByteArray()).getCode() != 42) throw new AssertionError("test protobuf round trip");
        if (!new ProtoUse().message().equals("hello")) throw new AssertionError("consumer cannot use generated type");
    }
}
`;

export const protobufCases:CaseDefinition[]=[{
  id:"BLD-02/first-unchanged-repeat",family:"BLD-02",apis:["API-043"],command:"java.protobuf.generateSources",
  sourceDirectory:"src/main/java",fixture:{"ProtoUse.java":PROTO_USE},prepare:prepareProtobuf,cleanup:cleanupProtobuf,
  variant:"real main/test protoc generation; independent compilation and serialization; type readiness; unchanged repeat",
  run:async c=>{
    await c.execute("java.project.getAll",[],v=>assert.deepEqual(v.map((uri:string)=>path.resolve(fileURLToPath(uri))),[path.resolve(c.fixture.root)]),"import_witness");
    // Buildship may disambiguate the project name. The command accepts that
    // imported Eclipse name, not the folder URI or Gradle rootProject.name.
    const projectDescription=readFileSync(path.join(c.fixture.root,".project"),"utf8");
    const projectName=/<projectDescription>\s*<name>([^<]+)<\/name>/u.exec(projectDescription)?.[1];
    c.assert("selected fixture was imported as a named Gradle project",!!projectName&&/^[\w.-]+$/u.test(projectName)&&projectDescription.includes("org.eclipse.buildship.core.gradleprojectnature"),{projectName,projectDescription});
    c.assert("import has not generated any source",Object.keys(generated(c)).length===0);
    await c.open("ProtoUse.java");
    await c.execute("java.protobuf.generateSources",[[projectName]],v=>assert.equal(v,null),"first_generation");
    const generation=c.operations.at(-1),first=generated(c);
    const expected=GENERATED_ROOTS.flatMap((root,i)=>(i?["TestGreeting","TestGreetingOrBuilder","TestGreetingSchema"]:["Greeting","GreetingOrBuilder","GreetingSchema"]).map(name=>root+"/bench/generated/"+name+".java")).sort();
    c.assert("both Gradle tasks generated exactly their declared Java outputs",JSON.stringify(Object.keys(first).sort())===JSON.stringify(expected),{actual:Object.keys(first).sort(),expected});
    writeFileSync(path.resolve(c.fixture.root,"../generated-first.json"),JSON.stringify(first,null,2)+"\n");
    for(const file of expected)c.registerFile(file,path.join(c.fixture.root,file));
    // The protocol client is responsible for delivering filesystem changes.
    // Gradle writes files externally; a successful command does not substitute
    // for this support notification. Its delivery remains on the action timeline.
    const changes=expected.map(file=>({uri:pathToFileURL(path.join(c.fixture.root,file)).href,type:1}));
    const watchTrigger=c.client.notify("workspace/didChangeWatchedFiles",{changes});
    c.mutations.push({kind:"generated_file_observation",changes,triggerNs:String(watchTrigger),generationOperationId:generation.operationId});
    const declaration=path.join(c.fixture.root,GENERATED_ROOTS[0],"bench/generated/Greeting.java"),source=readFileSync(declaration,"utf8");
    const params=()=>({textDocument:{uri:c.file("ProtoUse.java").uri},position:position(c.text("ProtoUse.java"),c.text("ProtoUse.java").indexOf("Greeting.newBuilder")+1)});
    const oracle=(v:any)=>exactLocations(v,[{uri:pathToFileURL(declaration).href,range:range(source,"Greeting",source.indexOf("public final class"))}]);
    // Generation and generated-type readiness have distinct request timers.
    // All immediate/retry/settled replies remain visible in the operation log.
    let readinessError:unknown;
    try{await c.transition("textDocument/definition",params,oracle,BigInt(generation.startNs),"generated Greeting resolves to its exact newly generated declaration after client file-change delivery");}catch(error){readinessError=error;}
    await c.execute("java.protobuf.generateSources",[[projectName]],v=>assert.equal(v,null),"unchanged_repeat");
    const repeated=generated(c);writeFileSync(path.resolve(c.fixture.root,"../generated-repeat.json"),JSON.stringify(repeated,null,2)+"\n");
    c.assert("unchanged generation preserves exact paths and bytes without duplicate declarations",JSON.stringify(repeated)===JSON.stringify(first),{first,repeated});
    try{await c.query("textDocument/definition",params(),oracle,"unchanged_repeat_type_ready");}catch(error){readinessError??=error;}
    // This is outside the watched project and after measured requests. An
    // unavailable server result must not suppress the independent code oracle.
    c.compileOracle(probe);
    if(readinessError)throw readinessError;
  },
}];
