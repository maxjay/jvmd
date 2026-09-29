import {test} from "node:test";
import assert from "node:assert/strict";
import {signatureOracle} from "../harness/oracles.ts";

test("a correct inactive overload cannot conceal the wrong active signature",()=>{
  const signatures=[{label:"join(String left, int right): String"},{label:"join(String left, long right): String"}];
  assert.throws(()=>signatureOracle({signatures,activeSignature:0,activeParameter:1},"join",["String","long"],1));
  signatureOracle({signatures,activeSignature:1,activeParameter:1},"join",["String","long"],1);
});
test("parameter order and return type cannot substitute for the required argument types",()=>{
  for(const label of ["join(int left, long right): String","join(long left, String right): String"])
    assert.throws(()=>signatureOracle({signatures:[{label}],activeParameter:1},"join",["String","long"],1));
});
test("signature-local active parameter takes precedence over the enclosing field",()=>{
  const result={signatures:[{label:"join(String left, long right): String",activeParameter:1}],activeParameter:0};
  signatureOracle(result,"join",["String","long"],1);
  assert.throws(()=>signatureOracle(result,"join",["String","long"],0));
});
test("protocol defaults select the first signature and first parameter",()=>{
  signatureOracle({signatures:[{label:"join(String left, int right): String"}],activeSignature:50,activeParameter:50},"join",["String","int"],0);
});
