DO $$
DECLARE
    v_date date;
    i int;
BEGIN
    -- Йдемо від 10 червня до 19 червня
    FOR i IN 0..9 LOOP
        v_date := date '2026-06-10' + i;

        RAISE NOTICE 'Генерація 10 млн рядків для дати: %', v_date;

        INSERT INTO public.audit_event_master (request_id, application_name, name, type, "timestamp")
        SELECT
            gen_random_uuid()::text,
            (ARRAY['diia-web', 'diia-app', 'diia-portal', 'diia-engine'])[floor(random() * 4 + 1)],
            'event_type_' || (floor(random() * 500))::text,
            'SYSTEM_EVENT',
            v_date::timestamp + (random() * interval '24 hours')
        FROM generate_series(1, 10000000);

        COMMIT;
    END LOOP;
END $$;