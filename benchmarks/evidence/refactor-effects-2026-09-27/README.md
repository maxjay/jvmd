# Refactor effect replay

At source `d7ad357fceb97e03ca07b58c2de1ef72af713f4f`, all five JDTLS refactors pass their semantic checks and independently authored compile/runtime probes. These include code-action discovery, renamed/reordered signature and callers, selected-expression extraction, resource move and interface extraction. No display-title matching is used.

| Route | JDTLS final outcome | JVMD |
| --- | --- | --- |
| Code-action refactor | pass | unsupported |
| Signature name and parameter order | pass | unsupported |
| Extract selection | protocol_error: shutdown exit 1 | unsupported |
| Move resource | pass | unsupported |
| Extract interface | protocol_error: shutdown exit 1 | unsupported |

Both failed shutdowns retain the successful operation/assertion evidence and the nonzero exit; neither process was forcibly killed. All ten reports finalized. Complete original inventory and all archived hashes verify; reducer integrity issues are empty. This one-block pipe-profile replay supplies correctness evidence, not a comparative performance claim. The archive retains original checksums and omits runtime caches; the manifest records verification before packaging.
