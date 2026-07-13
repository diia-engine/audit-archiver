CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. СТВОРЕННЯ МАЙСТЕР-ТАБЛИЦІ
CREATE TABLE public.audit_event_master
(
    id text NOT NULL DEFAULT uuid_generate_v4(),
    request_id text NOT NULL,
    application_name text NOT NULL,
    name text NOT NULL,
    type text NOT NULL,
    "timestamp" timestamp without time zone NOT NULL,
    user_keycloak_id text,
    user_name text,
    user_drfo text,
    source_system text,
    source_application text,
    source_business_process text,
    source_business_process_definition_id text,
    source_business_process_instance_id text,
    source_business_activity text,
    source_business_activity_id text,
    context text,
    received timestamp without time zone NOT NULL DEFAULT now(),
    
    CONSTRAINT audit_event__id__pk PRIMARY KEY (id, "timestamp"),
    CONSTRAINT audit_event__type__ck CHECK (type = ANY (ARRAY['USER_ACTION'::text, 'SECURITY_EVENT'::text, 'SYSTEM_EVENT'::text]))
) PARTITION BY RANGE ("timestamp");

-- 2. СТВОРЕННЯ VIEW ДЛЯ KAFKA
CREATE VIEW public.audit_event AS 
SELECT * FROM public.audit_event_master;

-- 3. ПРАВА ДОСТУПУ (спрощено для локалу)
-- (На локалі пропускаємо роздачу прав конкретним користувачам, якщо їх ще не створили)

-- 4. ІНІЦІАЛІЗАЦІЯ PG_PARTMAN
-- Встановлюємо сам екстеншен
CREATE SCHEMA IF NOT EXISTS partman;
CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA partman;

-- Налаштовуємо нарізку по ДНЯХ (бо тестувати місяці незручно)
SELECT partman.create_parent(
    p_parent_table := 'public.audit_event_master',
    p_control := 'timestamp',
    p_interval := '1 day',
    p_start_partition := '2026-06-04 00:00:00'::text, -- Початок
    p_premake := 4
);

UPDATE partman.part_config
SET infinite_time_partitions = true
WHERE parent_table = 'public.audit_event_master';

-- Легкий індекс
CREATE INDEX idx_audit_event_timestamp_brin 
ON public.audit_event_master USING brin ("timestamp");