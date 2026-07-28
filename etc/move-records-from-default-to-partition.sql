
-- Stats
SELECT
    date_trunc('day', "timestamp") AS day,
    count(*)
FROM audit_event_master_default
GROUP BY 1
ORDER BY 1;


-- Main script
BEGIN;

-- Копіюємо записи за день
INSERT INTO audit_event_master_move
SELECT *
FROM audit_event_master_default
WHERE "timestamp" >= DATE '2026-07-13'
  AND "timestamp" <  DATE '2026-07-14';

-- Видаляємо їх із DEFAULT
DELETE
FROM audit_event_master_default
WHERE "timestamp" >= DATE '2026-07-13'
  AND "timestamp" <  DATE '2026-07-14';

-- Створюємо партицію
CREATE TABLE audit_event_master_p2026_07_13
    PARTITION OF audit_event_master
        FOR VALUES FROM ('2026-07-13')
        TO ('2026-07-14');

-- Повертаємо записи через master
INSERT INTO audit_event_master
SELECT *
FROM audit_event_master_move;

TRUNCATE audit_event_master_move;

COMMIT;