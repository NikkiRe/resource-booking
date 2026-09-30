CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE resources (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('MEETING_ROOM', 'EQUIPMENT'))
);

CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES resources(id),
    booked_by VARCHAR(100) NOT NULL CHECK (length(trim(booked_by)) > 0),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    period TSTZRANGE GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    CHECK (ends_at > starts_at AND ends_at - starts_at <= INTERVAL '24 hours'),
    CHECK ((status = 'ACTIVE' AND cancelled_at IS NULL) OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)),
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (resource_id WITH =, period WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE TABLE idempotency_keys (
    request_key VARCHAR(128) PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
