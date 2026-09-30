/** LSP permits semantic rejection of a rename at a position without a target.
 * Internal errors, unavailable methods, cancellation and transport failures are
 * not evidence that the server understood the invalid position.
 */
export function isRenameRejection(error:any):boolean {
  return !!error && error.kind===undefined && [-32600,-32602,-32803].includes(error.code)
    && typeof error.message==="string" && /\b(renam(?:e|ing)|symbol|element|identifier|position)\b/iu.test(error.message);
}
export function isInvalidRenameRequest(method:string,state:string):boolean {
  return ["textDocument/prepareRename","textDocument/rename"].includes(method) && state==="invalid_position";
}
