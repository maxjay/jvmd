ALTER TABLE artifacts ADD COLUMN has_signature_edges INTEGER NOT NULL DEFAULT 0;
CREATE TABLE signature_targets (artifact_id INTEGER NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,src INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,target TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(artifact_id,src,target,kind)) WITHOUT ROWID;
CREATE INDEX signature_targets_src ON signature_targets(src,artifact_id);
CREATE INDEX signature_targets_target ON signature_targets(target,artifact_id);
CREATE TABLE artifact_edges (src_artifact INTEGER NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,src INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,dst_artifact INTEGER NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,dst INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,kind TEXT NOT NULL,PRIMARY KEY(src_artifact,src,dst_artifact,dst,kind)) WITHOUT ROWID;
CREATE INDEX artifact_edges_src ON artifact_edges(src,kind,src_artifact);
CREATE INDEX artifact_edges_dst ON artifact_edges(dst,kind,dst_artifact);
PRAGMA user_version=4;
