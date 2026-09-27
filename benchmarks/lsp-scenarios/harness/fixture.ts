import {mkdirSync,writeFileSync,readFileSync,readdirSync} from "node:fs";
import path from "node:path";
import {pathToFileURL} from "node:url";
import {createHash} from "node:crypto";

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
export const sha=(v:string|Buffer)=>createHash("sha256").update(v).digest("hex");
export type Fixture={root:string;files:Record<string,{path:string;uri:string;text:string}>;identity:string;inputs:Record<string,string>;classpath?:string[];
  settings?:Record<string,any>;environment?:Record<string,string>;preparation?:Record<string,any>;workspaceFolders?:{uri:string;name:string}[]};
export function createFixture(root:string,extra:Record<string,string>={},sourceDirectory=""):Fixture{
  mkdirSync(path.join(root,sourceDirectory,"bench"),{recursive:true});mkdirSync(path.join(root,".settings"),{recursive:true});
  const metadata:Record<string,string>={
    ".project":'<projectDescription><name>benchmark</name><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name><arguments/></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>',
    ".classpath":'<classpath><classpathentry kind="src" path="'+sourceDirectory+'"/><classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/><classpathentry kind="output" path="bin"/></classpath>',
    ".settings/org.eclipse.jdt.core.prefs":"eclipse.preferences.version=1\norg.eclipse.jdt.core.compiler.compliance=17\norg.eclipse.jdt.core.compiler.source=17\norg.eclipse.jdt.core.compiler.codegen.targetPlatform=17\n",
  };
  const files:Fixture["files"]={},inputs:Record<string,string>={};
  for(const [name,text] of Object.entries(metadata)){writeFileSync(path.join(root,name),text);inputs[name]=sha(text);}
  for(const [name,text] of Object.entries({...SOURCES,...extra})){
    const file=path.join(root,sourceDirectory,"bench",name);writeFileSync(file,text);files[name]={path:file,uri:pathToFileURL(file).href,text};inputs[path.join(sourceDirectory,"bench",name)]=sha(text);
  }
  return {root,files,identity:sha(JSON.stringify(inputs)),inputs};
}
export function inventory(root:string):Record<string,string>{
  const values:Record<string,string>={};
  const visit=(p:string)=>{for(const d of readdirSync(p,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){
    const file=path.join(p,d.name);if(d.isDirectory())visit(file);else if(d.isFile())values[path.relative(root,file)]=sha(readFileSync(file));
  }};visit(root);return values;
}
