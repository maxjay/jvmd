import assert from "node:assert/strict";
import path from "node:path";
import {spawnSync} from "node:child_process";
import {mkdirSync,readFileSync,writeFileSync,copyFileSync,chmodSync,rmSync} from "node:fs";
import {type Fixture,inventory,sha} from "./fixture.ts";
import {SETTINGS,type PreparationOptions} from "./ScenarioContext.ts";

export const PROTOBUF_PINS={
  gradleVersion:"9.1.0",gradleArchiveSha256:"a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806",
  // All 307 regular files of the verified official binary distribution, sorted
  // by relative path, encoded as path + NUL + SHA-256 + LF. No machine paths.
  gradleTreeSha256:"acaf7c1df152e0a1fb37eea6f5ef3095de912aaa727c89516fabf3ce4d81cadf",
  protocVersion:"3.21.4",protocSha256:"ae5ddc97b85d0a3a39db70c0eabe93e3f83a5d4617d354b932dc88f307e6e2ec",
  protobufJavaSha256:"85d0a5b8b7e14395cd4489533433cb18eae6b197b9b5030ac0e67e679beafa8e",
};
export const GENERATED_ROOTS=["build/generated/sources/proto/main/java","build/generated/sources/proto/test/java"];
export const PROTO_USE=`package bench;
import bench.generated.Greeting;
public class ProtoUse {
    public String message() { return Greeting.newBuilder().setMessage("hello").build().getMessage(); }
}
`;
const build=`plugins { id 'java'; id 'eclipse' }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType(JavaCompile).configureEach { options.release = 17 }
dependencies { implementation files('lib/protobuf-java-3.21.4.jar') }
sourceSets {
    main.java.srcDir 'build/generated/sources/proto/main/java'
    test.java.srcDir 'build/generated/sources/proto/test/java'
}
// These are real Gradle Exec tasks invoking the pinned compiler. Importing the
// model must not run them; java.protobuf.generateSources owns that transition.
tasks.register('generateProto', Exec) {
    inputs.file 'src/main/proto/greeting.proto'
    inputs.file 'tools/protoc'
    outputs.dir 'build/generated/sources/proto/main/java'
    commandLine file('tools/protoc').absolutePath, '--proto_path=src/main/proto', '--java_out=build/generated/sources/proto/main/java', 'src/main/proto/greeting.proto'
}
tasks.register('generateTestProto', Exec) {
    inputs.file 'src/test/proto/test_greeting.proto'
    inputs.file 'tools/protoc'
    outputs.dir 'build/generated/sources/proto/test/java'
    commandLine file('tools/protoc').absolutePath, '--proto_path=src/test/proto', '--java_out=build/generated/sources/proto/test/java', 'src/test/proto/test_greeting.proto'
}
eclipse.classpath.file.whenMerged { cp ->
    cp.entries.findAll { it.kind == 'src' && it.path.startsWith('build/generated/sources/proto/') }.each {
        it.entryAttributes['protobuf_generated_source'] = 'true'
    }
}
`;

export function prepareProtobuf(fixture:Fixture,javaHome:string,options:PreparationOptions={}){
  assert(process.platform==="linux"&&process.arch==="x64","pinned protoc fixture requires Linux x86_64");
  assert(options.gradleHome&&options.protoc&&options.protobufJava,"BLD-02 requires --gradle-home, --protoc and --protobuf-java; missing tools are not unsupported server capability");
  const gradleHome=path.resolve(options.gradleHome),protoc=path.resolve(options.protoc),jar=path.resolve(options.protobufJava);
  const tree=inventory(gradleHome),gradleTree=sha(Object.keys(tree).sort().map(p=>p+"\0"+tree[p]+"\n").join(""));
  assert.equal(gradleTree,PROTOBUF_PINS.gradleTreeSha256,"Gradle distribution differs from verified pin");
  assert.equal(sha(readFileSync(protoc)),PROTOBUF_PINS.protocSha256,"protoc differs from pin");
  assert.equal(sha(readFileSync(jar)),PROTOBUF_PINS.protobufJavaSha256,"protobuf runtime differs from pin");
  for(const relative of [".project",".classpath",".settings"])rmSync(path.join(fixture.root,relative),{recursive:true,force:true});
  for(const relative of ["lib","tools","src/main/proto","src/test/proto",...GENERATED_ROOTS])mkdirSync(path.join(fixture.root,relative),{recursive:true});
  copyFileSync(protoc,path.join(fixture.root,"tools/protoc"));chmodSync(path.join(fixture.root,"tools/protoc"),0o755);
  const runtime=path.join(fixture.root,"lib/protobuf-java-3.21.4.jar");copyFileSync(jar,runtime);fixture.classpath=[runtime];
  writeFileSync(path.join(fixture.root,"settings.gradle"),"rootProject.name = 'benchmark'\n");
  writeFileSync(path.join(fixture.root,"build.gradle"),build);
  writeFileSync(path.join(fixture.root,"gradle.properties"),"org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2\norg.gradle.parallel=false\norg.gradle.java.installations.auto-download=false\n");
  writeFileSync(path.join(fixture.root,"src/main/proto/greeting.proto"),'syntax = "proto3";\npackage bench.generated;\noption java_multiple_files = true;\noption java_outer_classname = "GreetingSchema";\nmessage Greeting { string message = 1; }\n');
  writeFileSync(path.join(fixture.root,"src/test/proto/test_greeting.proto"),'syntax = "proto3";\npackage bench.generated;\noption java_multiple_files = true;\noption java_outer_classname = "TestGreetingSchema";\nmessage TestGreeting { int32 code = 1; }\n');
  const userHome=path.resolve(fixture.root,"../gradle-user-home");mkdirSync(userHome,{recursive:true});
  fixture.environment={GRADLE_USER_HOME:userHome};
  fixture.settings=structuredClone(SETTINGS);
  fixture.settings.java.import.gradle={enabled:true,home:gradleHome,java:{home:javaHome},user:{home:userHome},offline:{enabled:true},wrapper:{enabled:false}};
  fixture.settings.java.configuration={runtimes:[{name:"JavaSE-25",path:javaHome,default:true}]};
  fixture.settings.java.jdt={ls:{protobufSupport:{enabled:true}}};
  fixture.preparation={kind:"real-protoc-gradle-tasks",pins:PROTOBUF_PINS,gradleHome,gradleUserHome:userHome,
    inputTreeSha256:gradleTree,preGeneratedSources:false,network:"offline Gradle; all dependencies are local pinned files",
    cleanupScope:"owned per-case Gradle user home; no shared daemon is stopped"};
  writeFileSync(path.resolve(fixture.root,"../protobuf-toolchain.json"),JSON.stringify(fixture.preparation,null,2)+"\n");
}

export function cleanupProtobuf(fixture:Fixture,javaHome:string){
  if(!fixture.preparation?.gradleHome)return;
  const command=[path.join(fixture.preparation.gradleHome,"bin/gradle"),"--stop","--offline","--console=plain"];
  const run=spawnSync(command[0],command.slice(1),{cwd:fixture.root,env:{...process.env,...fixture.environment,JAVA_HOME:javaHome},encoding:"utf8",timeout:30000});
  const result={command,status:run.status,signal:run.signal,stdout:run.stdout,stderr:run.stderr,error:String(run.error??"")};
  writeFileSync(path.resolve(fixture.root,"../gradle-cleanup.json"),JSON.stringify(result,null,2)+"\n");
  assert.equal(run.status,0,"owned Gradle daemon cleanup failed: "+run.stderr);
}
