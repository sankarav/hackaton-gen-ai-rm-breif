-- CRM schema for RM Pre-Meeting Brief
-- Seed data lives in V2__seed.sql (added in Step 2)

CREATE TABLE client (
    client_id           VARCHAR PRIMARY KEY,
    full_name           VARCHAR NOT NULL,
    segment             VARCHAR,               -- e.g. 'Mass Affluent', 'Private'
    relationship_start  DATE,
    rm_name             VARCHAR,
    plaid_access_token  VARCHAR,               -- Plaid item access_token (set during seed-client)
    plaid_item_id       VARCHAR
);

CREATE TABLE interaction (
    id            BIGSERIAL PRIMARY KEY,
    client_id     VARCHAR NOT NULL REFERENCES client(client_id),
    meeting_date  DATE    NOT NULL,
    notes         TEXT,
    promises      TEXT                         -- JSON array of follow-up promises
);

CREATE TABLE synthetic_product (               -- products Plaid won't model (e.g. CD, term deposit)
    id            BIGSERIAL PRIMARY KEY,
    client_id     VARCHAR NOT NULL REFERENCES client(client_id),
    product_type  VARCHAR,                     -- 'CD', 'TERM_DEPOSIT', etc.
    balance       NUMERIC(18, 2),
    maturity_date DATE,
    rate          NUMERIC(6, 4)
);

CREATE INDEX idx_interaction_client   ON interaction(client_id);
CREATE INDEX idx_interaction_date     ON interaction(client_id, meeting_date DESC);
CREATE INDEX idx_synthetic_prod_client ON synthetic_product(client_id);
