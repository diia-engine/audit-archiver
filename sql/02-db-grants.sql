GRANT CONNECT ON DATABASE audit TO audit_service_user;

GRANT USAGE, CREATE ON SCHEMA public TO audit_service_user;

GRANT USAGE ON SCHEMA partman TO audit_service_user;
GRANT ALL ON ALL TABLES IN SCHEMA partman TO audit_service_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA partman TO audit_service_user;

GRANT SELECT ON TABLE public.audit_event_archive TO audit_service_user;

ALTER TABLE public.audit_event_master
    OWNER TO audit_service_user;

DO
$$
    DECLARE
        partition_table REGCLASS;
    BEGIN
        FOR partition_table IN
            SELECT inhrelid::regclass
            FROM pg_inherits
            WHERE inhparent = 'public.audit_event_master'::regclass
            LOOP
                EXECUTE FORMAT(
                        'ALTER TABLE %s OWNER TO audit_service_user',
                        partition_table
                        );
            END LOOP;
    END
$$;