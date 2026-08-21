CREATE TABLE run (
    id                  BIGSERIAL PRIMARY KEY,
    spec_name           TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'CREATED',
    phase               TEXT NOT NULL DEFAULT 'CREATED',
    needs_human_reason  TEXT,
    done_summary        TEXT,
    page_count          INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE upload (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    kind          TEXT NOT NULL,
    filename      TEXT NOT NULL,
    content_type  TEXT,
    content       BYTEA NOT NULL,
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_upload_run ON upload(run_id);

CREATE TABLE page (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    page_no         INTEGER NOT NULL,
    png             BYTEA NOT NULL,
    text_layer      TEXT NOT NULL DEFAULT '',
    vision_caption  TEXT,
    UNIQUE (run_id, page_no)
);

CREATE TABLE section (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    section_no      INTEGER NOT NULL,
    title           TEXT NOT NULL,
    page_start      INTEGER NOT NULL,
    page_end        INTEGER NOT NULL,
    category_hint   TEXT,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    blocked_reason  TEXT,
    attempts        INTEGER NOT NULL DEFAULT 0,
    UNIQUE (run_id, section_no)
);
CREATE INDEX idx_section_run ON section(run_id);

CREATE TABLE test_case (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    section_id       BIGINT NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    seq_in_section   INTEGER NOT NULL,
    global_no        INTEGER,
    category_major   TEXT NOT NULL DEFAULT '',
    category_mid     TEXT NOT NULL DEFAULT '',
    category_minor   TEXT NOT NULL DEFAULT '',
    test_item        TEXT NOT NULL DEFAULT '',
    precondition     TEXT NOT NULL DEFAULT '',
    test_steps       TEXT NOT NULL DEFAULT '',
    expected_result  TEXT NOT NULL DEFAULT '',
    remark           TEXT NOT NULL DEFAULT ''
);
CREATE INDEX idx_test_case_run ON test_case(run_id);
CREATE INDEX idx_test_case_section ON test_case(section_id);
CREATE INDEX idx_test_case_global_no ON test_case(run_id, global_no);

CREATE TABLE question (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    section_id  BIGINT NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,
    text        TEXT NOT NULL,
    source      TEXT NOT NULL DEFAULT ''
);
CREATE INDEX idx_question_run ON question(run_id);

CREATE TABLE document (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL,
    content     TEXT NOT NULL DEFAULT '',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, kind)
);

CREATE TABLE log_line (
    id      BIGSERIAL PRIMARY KEY,
    run_id  BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    ts      TIMESTAMPTZ NOT NULL DEFAULT now(),
    line    TEXT NOT NULL
);
CREATE INDEX idx_log_line_run ON log_line(run_id, id);
