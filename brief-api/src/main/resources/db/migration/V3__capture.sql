-- Post-meeting capture: RM notes, photos of handwritten notes, voice memos and
-- (when compliance allows) consented meeting recordings, turned into a reviewed CRM entry.

CREATE TABLE recording_policy (
    scope              VARCHAR PRIMARY KEY,          -- 'GLOBAL' today; region / business line later
    recording_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         VARCHAR NOT NULL
);
INSERT INTO recording_policy (scope, recording_enabled, updated_by) VALUES ('GLOBAL', FALSE, 'seed');

CREATE TABLE capture (
    id                  BIGSERIAL PRIMARY KEY,
    client_id           VARCHAR NOT NULL REFERENCES client(client_id),
    meeting_date        DATE NOT NULL,
    audio_mode          VARCHAR,                     -- VOICE_MEMO | RECORDING | NULL (no audio)
    typed_notes         TEXT,
    state               VARCHAR NOT NULL,
    state_detail        TEXT,
    workflow_id         VARCHAR,
    interaction_id      BIGINT REFERENCES interaction(id) ON DELETE SET NULL,
    created_by          VARCHAR NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    review_reminder_at  TIMESTAMPTZ,
    decided_at          TIMESTAMPTZ,
    decided_by          VARCHAR,
    reject_reason       TEXT
);

CREATE TABLE capture_media (
    id             BIGSERIAL PRIMARY KEY,
    capture_id     BIGINT NOT NULL REFERENCES capture(id) ON DELETE CASCADE,
    kind           VARCHAR NOT NULL,                 -- IMAGE | AUDIO
    content_type   VARCHAR NOT NULL,
    original_name  VARCHAR,
    storage_key    VARCHAR NOT NULL,                 -- encrypted blob in the media store
    size_bytes     BIGINT NOT NULL,
    deleted_at     TIMESTAMPTZ
);

CREATE TABLE consent_record (
    id            BIGSERIAL PRIMARY KEY,
    capture_id    BIGINT NOT NULL UNIQUE REFERENCES capture(id) ON DELETE CASCADE,
    method        VARCHAR NOT NULL,                  -- CHECKBOX_AND_VERBAL
    consented_at  TIMESTAMPTZ NOT NULL,
    recorded_by   VARCHAR NOT NULL,
    withdrawn_at  TIMESTAMPTZ
);

CREATE TABLE transcript_segment (
    id                BIGSERIAL PRIMARY KEY,
    capture_id        BIGINT NOT NULL REFERENCES capture(id) ON DELETE CASCADE,
    seq               INT NOT NULL,
    source            VARCHAR NOT NULL,              -- TEXT | IMAGE:<mediaId> | AUDIO:<mediaId>
    start_sec         DOUBLE PRECISION,
    end_sec           DOUBLE PRECISION,
    speaker           VARCHAR NOT NULL,              -- RM | CLIENT | UNKNOWN
    speaker_inferred  BOOLEAN NOT NULL DEFAULT FALSE,
    low_confidence    BOOLEAN NOT NULL DEFAULT FALSE,
    text              TEXT NOT NULL,
    UNIQUE (capture_id, seq)
);

CREATE TABLE extracted_item (
    id               BIGSERIAL PRIMARY KEY,
    capture_id       BIGINT NOT NULL REFERENCES capture(id) ON DELETE CASCADE,
    category         VARCHAR NOT NULL,               -- SUMMARY | PROMISE | LIFE_EVENT | PRODUCT_INTEREST | CONCERN | NEXT_MEETING
    text             TEXT NOT NULL,
    owner            VARCHAR NOT NULL,               -- RM | CLIENT | UNKNOWN
    date_phrase      VARCHAR,                        -- verbatim from the source, never a model-computed date
    resolved_date    DATE,                           -- computed in Java from date_phrase + meeting_date
    product_mention  VARCHAR,                        -- verbatim from the source
    resolved_ref     VARCHAR,                        -- Plaid accountId or synthetic productType
    resolved_label   VARCHAR,
    source_seqs      VARCHAR NOT NULL,               -- comma-separated transcript_segment.seq values
    confidence       VARCHAR NOT NULL,               -- high | medium | low
    compliance_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    flags            TEXT,                           -- newline-separated problems; block approval until decided
    notes            TEXT,                           -- newline-separated informational notes
    decision         VARCHAR NOT NULL DEFAULT 'PENDING',  -- PENDING | ACCEPTED | REJECTED
    edited           BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE client_promise (
    id              BIGSERIAL PRIMARY KEY,
    client_id       VARCHAR NOT NULL REFERENCES client(client_id),
    interaction_id  BIGINT REFERENCES interaction(id) ON DELETE CASCADE,
    capture_id      BIGINT REFERENCES capture(id) ON DELETE SET NULL,
    text            TEXT NOT NULL,
    owner           VARCHAR NOT NULL,
    due_date        DATE,
    resolved_ref    VARCHAR,
    status          VARCHAR NOT NULL DEFAULT 'OPEN', -- OPEN | DONE | CANCELLED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ
);

-- IDs and actions only; never transcript or note content.
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor       VARCHAR NOT NULL,
    action      VARCHAR NOT NULL,
    client_id   VARCHAR,
    capture_id  BIGINT,
    detail      VARCHAR
);

CREATE INDEX idx_capture_client       ON capture(client_id, created_at DESC);
CREATE INDEX idx_capture_media        ON capture_media(capture_id);
CREATE INDEX idx_segment_capture      ON transcript_segment(capture_id, seq);
CREATE INDEX idx_item_capture         ON extracted_item(capture_id);
CREATE INDEX idx_promise_client_open  ON client_promise(client_id) WHERE status = 'OPEN';
CREATE INDEX idx_audit_capture        ON audit_log(capture_id);
