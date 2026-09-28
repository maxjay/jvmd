import assert from "node:assert/strict";
import path from "node:path";

/** JDTLS getSettings returns filesystem source paths; updateClasspaths passes
 * source and output paths to IProject.getFolder, requiring project-relative paths.
 * Binary/container entries keep their own encoding. */
export function classpathUpdateEntries(entries:any[],root:string){
  const relative=(value:string)=>{
    assert.equal(typeof value,"string");assert(path.isAbsolute(value),"reported source path must be absolute");
    const result=path.relative(root,value);
    assert(result!==".."&&!result.startsWith(".."+path.sep)&&!path.isAbsolute(result),"source/output path outside selected project");
    return result.split(path.sep).join("/")||".";
  };
  return entries.map(entry=>entry.kind!==3?structuredClone(entry):{
    ...structuredClone(entry),path:relative(entry.path),...(entry.output?{output:relative(entry.output)}:{})
  });
}
