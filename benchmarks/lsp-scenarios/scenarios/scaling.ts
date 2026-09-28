import assert from "node:assert/strict";
import {type CaseDefinition} from "../harness/ScenarioContext.ts";
import {scalingAxes,scalingBaseline,prepareScaling,type ScalingAxis} from "../harness/scaling.ts";
import {position,hoverOracle,completionOracle} from "../harness/oracles.ts";

export function scalingCases(axes:ScalingAxis[]=Object.keys(scalingAxes) as ScalingAxis[]):CaseDefinition[]{
  assert(new Set(axes).size===axes.length&&axes.every(a=>a in scalingAxes),"unknown or duplicate scaling axis");
  return axes.flatMap(axis=>scalingAxes[axis].flatMap(size=>["zero-change","unrelated-body","relevant-api"].map(mutation=>({
    id:`SCALE/${axis}-${size}-${mutation}`,family:"SCALE",apis:[axis==="members"?"API-044":"API-047","API-011"],
    capability:axis==="members"?"completionProvider":"hoverProvider",variant:`${axis}=${size}; independent ${mutation} control`,
    prepare:(fixture,javaHome)=>prepareScaling(fixture,javaHome,{...scalingBaseline,[axis]:size}),
    run:async c=>{
      const provider="modules/module_0/src/bench/Provider.java",probe="modules/module_0/src/bench/Probe.java",unrelated="modules/module_0/src/bench/UnrelatedBody.java";
      await c.open(provider);await c.open(probe);await c.open(unrelated);
      const enumeration=axis==="members",method=enumeration?"textDocument/completion":"textDocument/hover";
      const params=()=>({textDocument:{uri:c.file(probe).uri},position:position(c.text(probe),c.text(probe).indexOf(enumeration?"scaleMember0":"marker")+(enumeration?0:1))});
      const expected=Array.from({length:enumeration?size:1},(_,i)=>"scaleMember"+i);
      const oracle=(changed:boolean)=>(value:any)=>{
        if(!enumeration){hoverOracle(value,"marker",changed?"int":"String");return;}
        const names=[...expected,...(changed?["scaleChanged"]:[])];completionOracle(value,names,changed?[]:["scaleChanged"]);
        const items=Array.isArray(value)?value:value.items;
        const actual=items.filter((i:any)=>/^scale(?:Member\d+|Changed)(?:\(|$)/u.test(i.label)).map((i:any)=>i.label.split("(")[0]).sort();
        assert.deepEqual(actual,[...names].sort(),"enumeration has duplicated or unexpected members");
      };
      await c.series(method,params(),oracle(false));
      const file=mutation==="unrelated-body"?unrelated:provider,original=c.text(file);
      const changed=mutation==="zero-change"?original:mutation==="unrelated-body"?original.replace("return 1;","return 2;"):
        enumeration?original.replace(/\}\n$/u," public int scaleChanged(){return 999;} }\n"):original.replace('public String marker = "saved"','public int marker = 42');
      if(mutation!=="zero-change")assert.notEqual(changed,original);
      const {trigger}=c.change(file,changed);
      await c.transition(method,params,oracle(mutation==="relevant-api"),trigger,
        mutation==="relevant-api"?"distinct provider API visible in unchanged caller":"query result preserved by declared semantic control; does not prove mutation processing or zero native work");
    },
  }))));
}
