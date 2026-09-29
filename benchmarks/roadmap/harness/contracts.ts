export type DiagnosticVersionMode = "unknown"|"versioned"|"versionless";
export class UnavailableEvidence extends Error {
  evidence:any;
  constructor(reason:string,evidence:any){super(reason);this.name="UnavailableEvidence";this.evidence=evidence;}
}

export function diagnosticDecision(mode:DiagnosticVersionMode, params:any, uri:string, version:number,
  incarnation=1, eventIncarnation?:number):{kind:"ignore"|"verified"|"unavailable";mode:DiagnosticVersionMode}{
  if (params?.uri !== uri) return {kind:"ignore",mode};
  if (eventIncarnation !== undefined && eventIncarnation !== incarnation) return {kind:"ignore",mode};
  if (Number.isInteger(params?.version) && params.version === version) {
    if (incarnation > 1 && eventIncarnation === undefined) return {kind:"unavailable",mode};
    return {kind:"verified",mode:"versioned"};
  }
  if (params?.version === undefined && mode !== "versioned") return {kind:"unavailable",mode:"versionless"};
  return {kind:"ignore",mode};
}
