CREATE TABLE artifact_symbols (artifact_id INTEGER NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE, symbol_id INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE, data TEXT, source_file TEXT, PRIMARY KEY(artifact_id,symbol_id)) WITHOUT ROWID;
CREATE INDEX artifact_symbols_symbol ON artifact_symbols(symbol_id,artifact_id);
CREATE INDEX artifact_symbols_source ON artifact_symbols(artifact_id,source_file);
INSERT INTO artifact_symbols(artifact_id,symbol_id) SELECT artifact_id,id FROM symbols;
PRAGMA user_version=2;
