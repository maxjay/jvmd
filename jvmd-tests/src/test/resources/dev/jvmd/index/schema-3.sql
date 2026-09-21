ALTER TABLE artifacts ADD COLUMN has_class_refs INTEGER NOT NULL DEFAULT 0;
CREATE TABLE artifact_class_refs (artifact_id INTEGER NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,target TEXT NOT NULL,PRIMARY KEY(artifact_id,target)) WITHOUT ROWID;
CREATE INDEX artifact_class_refs_target ON artifact_class_refs(target,artifact_id);
CREATE TABLE code_targets (artifact_id INTEGER NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,src INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,target TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(artifact_id,src,target,kind)) WITHOUT ROWID;
CREATE INDEX code_targets_target ON code_targets(target,artifact_id);
CREATE INDEX code_targets_src ON code_targets(src,artifact_id);
PRAGMA user_version=3;
