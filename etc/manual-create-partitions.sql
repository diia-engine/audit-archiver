SELECT partman.create_partition_time(
    p_parent_table := 'public.audit_event_master'::text,
    p_partition_times := ARRAY[
        '2026-06-06 00:00:00+00',
        '2026-06-07 00:00:00+00'
    ]::timestamptz[]
);