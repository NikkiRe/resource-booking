WITH bounds AS (
    SELECT md5('benchmark-resource-' || CAST(:resource_number AS text))::uuid AS resource_id,
           DATE '2020-01-01' + CAST(:day_offset AS integer) AS from_day,
           DATE '2020-01-01' + CAST(:day_offset AS integer) + 31 AS to_day
), daily AS (
    SELECT b.resource_id, d.utc_start::date AS day,
           count(*) AS booking_count,
           sum(extract(epoch FROM
               least(b.ends_at, (d.utc_start + INTERVAL '1 day') AT TIME ZONE 'UTC') -
               greatest(b.starts_at, d.utc_start AT TIME ZONE 'UTC')
           )) AS booked_seconds
    FROM bounds q
    JOIN bookings b ON b.resource_id = q.resource_id
        AND b.status = 'ACTIVE'
        AND b.period && tstzrange(q.from_day::timestamp AT TIME ZONE 'UTC',
                                  q.to_day::timestamp AT TIME ZONE 'UTC', '[)')
    CROSS JOIN LATERAL generate_series(
        greatest(date_trunc('day', b.starts_at AT TIME ZONE 'UTC'), q.from_day::timestamp),
        least(date_trunc('day', b.ends_at AT TIME ZONE 'UTC'), q.to_day::timestamp - INTERVAL '1 day'),
        INTERVAL '1 day'
    ) AS d(utc_start)
    WHERE b.ends_at > d.utc_start AT TIME ZONE 'UTC'
    GROUP BY b.resource_id, d.utc_start::date
)
SELECT r.id, r.name, q.from_day + g.n AS day,
       coalesce(u.booking_count, 0) AS booking_count,
       coalesce(u.booked_seconds, 0) AS booked_seconds,
       round(coalesce(u.booked_seconds, 0) * 100 / 86400, 4) AS utilization_percent
FROM bounds q
JOIN resources r ON r.id = q.resource_id
CROSS JOIN generate_series(0, 30) AS g(n)
LEFT JOIN daily u ON u.resource_id = r.id AND u.day = q.from_day + g.n
ORDER BY day;
