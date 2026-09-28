# First persisted-state reopen capture

Source `fb4cdfe1ce2e209f04f997397aee52433594c523`. JVMD passes the complete pipe-profile case: saved String baseline, unsaved int overlay, first clean shutdown, identical nonempty persisted-state snapshots, fresh process/client and restored saved String result, followed by clean shutdown. Both independent protocol/process histories are preserved. This does not establish Unix daemon behaviour or a cache hit.

JDTLS passes its saved baseline, returns empty hover content immediately after the unsaved edit, and later exits 1 during shutdown. It does not reach restart. The capture therefore provides no JDTLS persisted-reopen result. The next source uses the already-declared bounded transition policy before restart, while carrying any failed immediate result into the final case outcome.

Both reports finalized; complete original inventory and selected hashes verify, with no reducer integrity issues. The archive contains seed-session journals when reached, original checksums and explicit omissions. No public comparative performance claim follows from this pilot.
