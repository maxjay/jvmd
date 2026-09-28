import test from "node:test";
import assert from "node:assert/strict";
import {scalingModel,scalingBaseline,scalingAxes} from "../harness/scaling.ts";
import {scalingCases} from "../scenarios/scaling.ts";
test("source growth preserves the query and dependency graph while module growth only redistributes padding",()=>{
  const baseline=scalingModel(scalingBaseline);
  for(const sources of scalingAxes.sources){const model=scalingModel({...scalingBaseline,sources});
    assert.deepEqual(model.edges,baseline.edges);assert.equal(Object.keys(model.files).length,sources+68);
    for(const [file,text] of Object.entries(baseline.files))if(file.startsWith("modules/module_0/src/bench/"))assert.equal(model.files[file],text);
  }
  for(const modules of scalingAxes.modules){const model=scalingModel({...scalingBaseline,modules});
    assert.equal(Object.keys(model.files).length,Object.keys(baseline.files).length);
    assert.deepEqual(Object.values(model.files).sort(),Object.values(baseline.files).sort());
    assert.deepEqual(model.edges,baseline.edges);
  }
});
test("dependency reach changes only caller edges; member enumeration changes only the queried type",()=>{
  const base=scalingModel(scalingBaseline);
  for(const reach of scalingAxes.reach){const m=scalingModel({...scalingBaseline,reach});assert.equal(m.edges.filter(e=>e.to==="modules/module_0/src/bench/Provider.java").length,reach);
    assert.equal(m.edges.length,64);assert.equal(Object.keys(m.files).length,Object.keys(base.files).length);
    for(const [file,text] of Object.entries(base.files))if(!file.includes("Caller"))assert.equal(m.files[file],text);
  }
  for(const members of scalingAxes.members){const m=scalingModel({...scalingBaseline,members});assert.deepEqual(m.edges,base.edges);
    assert.equal([...m.files["modules/module_0/src/bench/Provider.java"].matchAll(/public int scaleMember\d+\(/gu)].length,members);
    for(const [file,text] of Object.entries(base.files))if(!file.endsWith("/Provider.java"))assert.equal(m.files[file],text);
  }
  assert.deepEqual(scalingModel(scalingBaseline),base,"generator is nondeterministic");
});
test("three sizes and independent mutation controls are executable for all five axes",()=>{
  const cases=scalingCases();assert.equal(cases.length,45);assert.equal(new Set(cases.map(c=>c.id)).size,45);
  for(const axis of Object.keys(scalingAxes))for(const size of scalingAxes[axis])for(const mutation of ["zero-change","unrelated-body","relevant-api"])
    assert(cases.some(c=>c.id===`SCALE/${axis}-${size}-${mutation}`));
  assert.throws(()=>scalingCases(["invalid"] as any));assert.throws(()=>scalingModel({...scalingBaseline,reach:65}));
});
