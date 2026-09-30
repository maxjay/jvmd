import {mkdirSync,writeFileSync,readFileSync,readdirSync} from "node:fs";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {createHash} from "node:crypto";
import {pomXml,artifactJar,type Dependency,type Pom} from "./maven.ts";

export const SOURCES:Record<string,string>={
  "Customer.java":`package bench;
/** A customer in the exact benchmark fixture. */
public class Customer {
    public String label = "Ada";
    /** NAME_DOC_V1 */
    public String name() { return label; }
    /** NUMBER_DOC_V1 */
    public int number() { return 7; }
    /** JOIN_DOC_V1 */
    public String join(String left, int right) { return left + right; }
}
`,
  "Use.java":`package bench;
public class Use {
    public String read(Customer customer) { return customer.name(); }
    public int count(Customer customer) { return customer.number(); }
    public int twice(Customer customer) { return customer.number() + customer.number(); }
    public String join(Customer customer) { return customer.join("a", 2); }
}
`,
  "Unrelated.java":`package bench;
public class Unrelated {
    public String name() { return "unrelated"; }
    public String label = "unrelated";
    public int number() { return 73; }
}
`,
  "Hierarchy.java":`package bench;
interface Root { int value(); }
class Base implements Root { public int value() { return 1; } }
class Child extends Base { public int value() { return 2; } }
class UnrelatedType { public int value() { return 3; } }
`,
  "Calls.java":`package bench;
public class Calls {
    public int a() { return b() + b(); }
    public int b() { return c(); }
    public int c() { return 3; }
    public int d() { return 4; }
}
`,
  "Format.java":`package bench;
public class Format {public int value( ){return 1+2;}}
`,
  "Generate.java":`package bench;
public class Generate {
    private String name;
    private int number;
    private Customer delegate;
}
`,
};
/** Maven's conventional source roots; every fixture is a plain Maven project. */
export const MAIN="src/main/java",TEST="src/test/java";
export const sha=(v:string|Buffer)=>createHash("sha256").update(v).digest("hex");
export type FixtureFile={path:string;uri:string;text:string};
export type Fixture={root:string;repository:string;pom:Pom;files:Record<string,FixtureFile>;identity:string;
  settings?:Record<string,any>;environment?:Record<string,string>;preparation?:Record<string,any>;workspaceFolders?:{uri:string;name:string}[]};

/** A Maven project: pom.xml plus sources under src/main/java/bench, resolving dependencies from the shared repository. */
export function createFixture(root:string,repository:string,extra:Record<string,string>={},{artifactId="benchmark",base=true}={}):Fixture{
  const fixture:Fixture={root,repository,pom:{groupId:"bench",artifactId,version:"1",release:17,dependencies:[]},files:{},identity:""};
  mkdirSync(path.join(root,MAIN,"bench"),{recursive:true});
  for(const [name,text] of Object.entries({...(base?SOURCES:{}),...extra}))addSource(fixture,name,path.join(MAIN,"bench",name),text);
  writePom(fixture);return fixture;
}
export function writePom(fixture:Fixture){
  const text=pomXml(fixture.pom);writeFileSync(path.join(fixture.root,"pom.xml"),text);
  fixture.identity=sha(JSON.stringify([text,...Object.values(fixture.files).map(f=>[path.relative(fixture.root,f.path),f.text])]));
  return text;
}
export function addSource(fixture:Fixture,name:string,relative:string,text:string){
  const file=path.join(fixture.root,relative);mkdirSync(path.dirname(file),{recursive:true});writeFileSync(file,text);
  fixture.files[name]={path:file,uri:pathToFileURL(file).href,text};return fixture.files[name];
}
export function addDependency(fixture:Fixture,dependency:Dependency){fixture.pom.dependencies.push(dependency);writePom(fixture);}
/** Dependency jars for the independent javac oracle. */
export const classpath=(fixture:Fixture,scopes=["compile"])=>fixture.pom.dependencies.filter(d=>scopes.includes(d.scope??"compile")).map(d=>artifactJar(fixture.repository,d));
/** A second Maven project next to the fixture, opened as another workspace folder. */
export function addProject(fixture:Fixture,directory:string,artifactId:string,files:Record<string,string>){
  const other=createFixture(path.resolve(fixture.root,"..",directory),fixture.repository,files,{artifactId,base:false});
  fixture.workspaceFolders=[...(fixture.workspaceFolders??[{uri:pathToFileURL(fixture.root).href,name:fixture.pom.artifactId}]),{uri:pathToFileURL(other.root).href,name:artifactId}];
  return other;
}
export function inventory(root:string):Record<string,string>{
  const values:Record<string,string>={};
  const visit=(p:string)=>{for(const d of readdirSync(p,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){
    const file=path.join(p,d.name);if(d.isDirectory())visit(file);else if(d.isFile())values[path.relative(root,file)]=sha(readFileSync(file));
  }};visit(root);return values;
}
