CREATE TABLE reference_chunk (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    chunk_no    INTEGER NOT NULL,
    content     TEXT NOT NULL,
    embedding   TEXT,
    UNIQUE (run_id, chunk_no)
);

CREATE INDEX idx_reference_chunk_run ON reference_chunk(run_id);
