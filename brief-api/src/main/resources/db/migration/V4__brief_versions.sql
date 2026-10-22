-- Generated briefs are kept so the RM can refine them in chat. Every applied edit is a new
-- version; earlier versions are never modified. Chat transcripts are deliberately not stored.

CREATE TABLE brief (
    id               BIGSERIAL PRIMARY KEY,
    client_id        VARCHAR NOT NULL REFERENCES client(client_id),
    change_report    TEXT NOT NULL,                 -- ChangeReport JSON the brief was generated from
    current_version  INT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE brief_version (
    id                BIGSERIAL PRIMARY KEY,
    brief_id          BIGINT NOT NULL REFERENCES brief(id) ON DELETE CASCADE,
    version           INT NOT NULL,
    content           TEXT NOT NULL,                -- BriefSchema JSON
    citation_details  TEXT NOT NULL,                -- {id: {label, detail}} for citations outside the ChangeReport
    created_by        VARCHAR NOT NULL,
    change_note       VARCHAR,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (brief_id, version)
);

CREATE INDEX idx_brief_client ON brief(client_id, created_at DESC);
