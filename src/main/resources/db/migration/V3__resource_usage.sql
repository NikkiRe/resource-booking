CREATE INDEX bookings_active_resource_start
    ON bookings (resource_id, starts_at) INCLUDE (ends_at)
    WHERE status = 'ACTIVE';

CREATE MATERIALIZED VIEW resource_daily_usage AS
SELECT b.resource_id,
       d.utc_start::date AS day,
       count(*) AS booking_count,
       sum(extract(epoch FROM
           least(b.ends_at, (d.utc_start + INTERVAL '1 day') AT TIME ZONE 'UTC') -
           greatest(b.starts_at, d.utc_start AT TIME ZONE 'UTC')
       )) AS booked_seconds
FROM bookings b
CROSS JOIN LATERAL generate_series(
    date_trunc('day', b.starts_at AT TIME ZONE 'UTC'),
    date_trunc('day', b.ends_at AT TIME ZONE 'UTC'),
    INTERVAL '1 day'
) AS d(utc_start)
WHERE b.status = 'ACTIVE' AND b.ends_at > d.utc_start AT TIME ZONE 'UTC'
GROUP BY b.resource_id, d.utc_start::date;

CREATE UNIQUE INDEX resource_daily_usage_resource_day
    ON resource_daily_usage (resource_id, day);

CREATE TABLE resource_usage_refresh_state (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    as_of TIMESTAMPTZ,
    refreshed_at TIMESTAMPTZ,
    CHECK ((as_of IS NULL) = (refreshed_at IS NULL))
);

INSERT INTO resource_usage_refresh_state (singleton) VALUES (true);
