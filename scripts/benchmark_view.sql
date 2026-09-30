WITH bounds AS (
    SELECT md5('benchmark-resource-' || CAST(:resource_number AS text))::uuid AS resource_id,
           DATE '2020-01-01' + CAST(:day_offset AS integer) AS from_day
)
SELECT r.id, r.name, q.from_day + g.n AS day,
       coalesce(u.booking_count, 0) AS booking_count,
       coalesce(u.booked_seconds, 0) AS booked_seconds,
       round(coalesce(u.booked_seconds, 0) * 100 / 86400, 4) AS utilization_percent
FROM bounds q
JOIN resources r ON r.id = q.resource_id
CROSS JOIN generate_series(0, 30) AS g(n)
LEFT JOIN resource_daily_usage u ON u.resource_id = r.id AND u.day = q.from_day + g.n
ORDER BY day;
