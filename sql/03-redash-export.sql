/*
  Run this file on the Admin Redash PostgreSQL database.

  It returns exactly one row and one column, sql_script. Save/copy that single
  value locally from the pgAdmin result grid as a UTF-8 .sql file, then execute
  the saved file on Viewer. PostgreSQL cannot write to the pgAdmin computer's
  filesystem directly. No COPY, psql, sequence manipulation, or Admin
  identifiers are used by the generated INSERT statements.

  Filters are optional exact-match filters on Admin data:
    dashboard_filter = ARRAY['dashboard-slug-1', 'dashboard-slug-2']
    query_filter     = ARRAY['Query name 1', 'Query name 2']
  NULL means "all" for the respective entity type. A non-NULL empty array
  means "none". Queries required by selected dashboard widgets are always
  included, even when query_filter is non-NULL.

  Viewer records are upserted by name: queries by (org, datasource, name) and
  dashboards by (org, name). Re-running the generated script updates those
  records instead of creating copies. Dashboard widgets are replaced so their
  visualization references and layout are also refreshed.

  Only Admin queries whose data_source_id belongs to datasource_name +
  datasource_type are exported. Dashboards containing a widget from another
  datasource are skipped as a whole, so no broken dashboard is created.

  target_org_id is a Viewer organization ID. It is deliberately explicit:
  organization IDs are instance-local and organizations are out of this
  migration scope.
*/
WITH
config AS (
    SELECT
        's3-audit'::varchar(255) AS datasource_name,
        'trino'::varchar(255) AS datasource_type,
        1::integer AS target_user_id,
        1::integer AS target_org_id,
        NULL::text[] AS dashboard_filter,
        NULL::text[] AS query_filter
),
source_datasource_ids AS (
    /* Only queries attached to this Admin datasource may be exported. */
    SELECT ds.id
    FROM data_sources ds
    CROSS JOIN config c
    WHERE ds.name = c.datasource_name
      AND ds.type = c.datasource_type
),
source_queries AS (
    SELECT q.*
    FROM queries q
    WHERE q.data_source_id IN (SELECT id FROM source_datasource_ids)
),
candidate_dashboards AS (
    SELECT d.*
    FROM dashboards d
    CROSS JOIN config c
    WHERE c.dashboard_filter IS NULL
       OR d.slug = ANY (c.dashboard_filter)
),
selected_dashboards AS (
    /*
      Keep only complete Trino dashboards: at least one widget must reference
      a configured-datasource query, and a mixed-source dashboard cannot be
      moved safely when this utility migrates only that datasource. This also
      excludes dashboards that contain only text widgets.
    */
    SELECT d.*
    FROM candidate_dashboards d
    WHERE EXISTS (
        SELECT 1
        FROM widgets w
        JOIN visualizations v ON v.id = w.visualization_id
        WHERE w.dashboard_id = d.id
          AND v.query_id IN (SELECT id FROM source_queries)
    )
      AND NOT EXISTS (
        SELECT 1
        FROM widgets w
        JOIN visualizations v ON v.id = w.visualization_id
        WHERE w.dashboard_id = d.id
          AND v.query_id NOT IN (SELECT id FROM source_queries)
    )
),
selected_widgets AS (
    SELECT w.*
    FROM widgets w
    JOIN selected_dashboards d ON d.id = w.dashboard_id
),
widget_visualization_ids AS (
    SELECT DISTINCT w.visualization_id AS id
    FROM selected_widgets w
    WHERE w.visualization_id IS NOT NULL
),
required_query_ids AS (
    SELECT DISTINCT v.query_id AS id
    FROM visualizations v
    JOIN widget_visualization_ids wv ON wv.id = v.id
),
selected_queries AS (
    SELECT q.*
    FROM source_queries q
    CROSS JOIN config c
    WHERE q.id IN (SELECT id FROM required_query_ids)
       OR c.query_filter IS NULL
       OR q.name = ANY (c.query_filter)
),
selected_visualizations AS (
    SELECT v.*
    FROM visualizations v
    JOIN selected_queries q ON q.id = v.query_id
),
scope_stats AS (
    SELECT
        (SELECT count(*) FROM source_queries) AS source_query_count,
        (SELECT count(*) FROM candidate_dashboards) AS candidate_dashboard_count,
        (SELECT count(*) FROM selected_dashboards) AS selected_dashboard_count
),
script AS (
    /* This comment is included in the Viewer script as an explicit hand-off notice. */
    SELECT   1 AS section, 0::integer AS entity_id, 10 AS line_no,
            '-- Generated Redash migration script. Save this text locally from pgAdmin before executing it on Viewer.'::text AS line
    UNION ALL
    /* DECLARE */
    SELECT  10 AS section, 0::integer AS entity_id,  10 AS line_no,
            'DECLARE'::text AS line
    UNION ALL
    SELECT  10, 0,  20, '    v_datasource_id integer;'
    UNION ALL
    SELECT  10, 0,  30, '    v_user_id integer := ' || c.target_user_id::text || ';'
    FROM config c
    UNION ALL
    SELECT  10, 0,  40, '    v_match_count integer;'
    UNION ALL
    SELECT  10, 0,  50, '    v_target_org_id integer := ' || c.target_org_id::text || ';'
    FROM config c
    UNION ALL
    SELECT  10, q.id, 100,
            format('    v_query_%s integer;', q.id)
    FROM selected_queries q
    UNION ALL
    SELECT  10, v.id, 200,
            format('    v_visualization_%s integer;', v.id)
    FROM selected_visualizations v
    UNION ALL
    SELECT  10, d.id, 300,
            format('    v_dashboard_%s integer;', d.id)
    FROM selected_dashboards d
    UNION ALL
    SELECT  10, w.id, 400,
            format('    v_widget_%s integer;', w.id)
    FROM selected_widgets w
    UNION ALL
    SELECT  20, 0, 10, 'BEGIN'
    UNION ALL

    /* Viewer-side dependency checks */
    SELECT  30, 0, 10, '-- Configuration'
    UNION ALL
    SELECT  30, 0, 20,
            format('    -- target_org_id: %s', c.target_org_id)
    FROM config c
    UNION ALL
    SELECT  30, 0, 25,
            format('    -- target_user_id: %s', c.target_user_id)
    FROM config c
    UNION ALL
    SELECT  30, 0, 30,
            format('    -- Admin scope: %s configured-datasource queries; %s/%s dashboards selected (mixed-datasource dashboards skipped).', s.source_query_count, s.selected_dashboard_count, s.candidate_dashboard_count)
    FROM scope_stats s
    UNION ALL
    SELECT  40, 0, 10, '-- Find datasource'
    UNION ALL
    SELECT  40, 0, 20,
            format(
                '    SELECT count(*), min(id) INTO v_match_count, v_datasource_id FROM data_sources WHERE org_id = v_target_org_id AND name = %L AND type = %L;',
                c.datasource_name, c.datasource_type
            )
    FROM config c
    UNION ALL
    SELECT  40, 0, 30,
            '    IF v_match_count = 0 THEN RAISE EXCEPTION ''Datasource not found in Viewer: org_id=%, name=%, type=%'', v_target_org_id, ' ||
            quote_literal(c.datasource_name) || ', ' || quote_literal(c.datasource_type) || '; END IF;'
    FROM config c
    UNION ALL
    SELECT  40, 0, 40,
            '    IF v_match_count > 1 THEN RAISE EXCEPTION ''Datasource is ambiguous in Viewer: org_id=%, name=%, type=%'', v_target_org_id, ' ||
            quote_literal(c.datasource_name) || ', ' || quote_literal(c.datasource_type) || '; END IF;'
    FROM config c
    UNION ALL
    SELECT  50, 0, 10, '-- Find user'
    UNION ALL
    SELECT  50, 0, 20,
            '    SELECT count(*) INTO v_match_count FROM users WHERE id = v_user_id AND org_id = v_target_org_id;'
    UNION ALL
    SELECT  50, 0, 30,
            '    IF v_match_count = 0 THEN RAISE EXCEPTION ''User not found in Viewer: id=%, org_id=%'', v_user_id, v_target_org_id; END IF;'
    UNION ALL

    /* Queries: query_results are intentionally not migrated. */
    SELECT  60, 0, 10, '-- Queries (upsert by organization, datasource, and name)'
    UNION ALL
    SELECT  60, q.id, 20,
            format(
                '    SELECT count(*), min(id) INTO v_match_count, v_query_%s FROM queries WHERE org_id = v_target_org_id AND data_source_id = v_datasource_id AND name = %L; IF v_match_count > 1 THEN RAISE EXCEPTION ''Multiple Viewer queries match name %% for org_id=%% and datasource_id=%%'', %L, v_target_org_id, v_datasource_id; END IF; IF v_match_count = 0 THEN INSERT INTO queries (updated_at, created_at, version, org_id, data_source_id, latest_query_data_id, name, description, query, query_hash, api_key, user_id, last_modified_by_id, is_archived, is_draft, schedule, schedule_failures, options, search_vector, tags) VALUES (%L::timestamptz, %L::timestamptz, %s, v_target_org_id, v_datasource_id, NULL, %L, %s, %L, %L, %L, v_user_id, v_user_id, %L::boolean, %L::boolean, %s, %s, %L, %s, %s) RETURNING id INTO v_query_%s; ELSE UPDATE queries SET updated_at = %L::timestamptz, version = %s, latest_query_data_id = NULL, description = %s, query = %L, query_hash = %L, api_key = %L, user_id = v_user_id, last_modified_by_id = v_user_id, is_archived = %L::boolean, is_draft = %L::boolean, schedule = %s, schedule_failures = %s, options = %L, search_vector = %s, tags = %s WHERE id = v_query_%s; END IF;',
                q.id,
                q.name,
                q.name,
                q.updated_at::text,
                q.created_at::text,
                q.version,
                q.name,
                CASE WHEN q.description IS NULL THEN 'NULL' ELSE format('%L', q.description) END,
                q.query,
                q.query_hash,
                q.api_key,
                q.is_archived::text,
                q.is_draft::text,
                CASE WHEN q.schedule IS NULL THEN 'NULL' ELSE format('%L', q.schedule) END,
                q.schedule_failures,
                q.options,
                CASE WHEN q.search_vector IS NULL THEN 'NULL' ELSE format('%L::tsvector', q.search_vector::text) END,
                CASE WHEN q.tags IS NULL THEN 'NULL' ELSE format('%L::varchar[]', q.tags::text) END,
                q.id,
                q.updated_at::text,
                q.version,
                CASE WHEN q.description IS NULL THEN 'NULL' ELSE format('%L', q.description) END,
                q.query,
                q.query_hash,
                q.api_key,
                q.is_archived::text,
                q.is_draft::text,
                CASE WHEN q.schedule IS NULL THEN 'NULL' ELSE format('%L', q.schedule) END,
                q.schedule_failures,
                q.options,
                CASE WHEN q.search_vector IS NULL THEN 'NULL' ELSE format('%L::tsvector', q.search_vector::text) END,
                CASE WHEN q.tags IS NULL THEN 'NULL' ELSE format('%L::varchar[]', q.tags::text) END,
                q.id
            )
    FROM selected_queries q
    UNION ALL

    /*
      There is no stable source identifier on Viewer for a visualization. Match
      all of its content fields, rather than name alone: a Redash query may
      legitimately contain several unnamed visualizations.
    */
    SELECT  70, 0, 10, '-- Visualizations (reuse identical records)'
    UNION ALL
    SELECT  70, v.id, 20,
            format(
                '    SELECT count(*), min(id) INTO v_match_count, v_visualization_%s FROM visualizations WHERE query_id = v_query_%s AND type = %L AND name IS NOT DISTINCT FROM %L AND description IS NOT DISTINCT FROM %s AND options IS NOT DISTINCT FROM %L; IF v_match_count > 1 THEN RAISE EXCEPTION ''Multiple Viewer visualizations have identical content for query %%'', v_query_%s; END IF; IF v_match_count = 0 THEN INSERT INTO visualizations (updated_at, created_at, type, query_id, name, description, options) VALUES (%L::timestamptz, %L::timestamptz, %L, v_query_%s, %L, %s, %L) RETURNING id INTO v_visualization_%s; END IF;',
                v.id,
                v.query_id,
                v.type,
                v.name,
                CASE WHEN v.description IS NULL THEN 'NULL' ELSE format('%L', v.description) END,
                v.options,
                v.query_id,
                v.updated_at::text,
                v.created_at::text,
                v.type,
                v.query_id,
                v.name,
                CASE WHEN v.description IS NULL THEN 'NULL' ELSE format('%L', v.description) END,
                v.options,
                v.id
            )
    FROM selected_visualizations v
    UNION ALL

    /* Dashboards */
    SELECT  80, 0, 10, '-- Dashboards (upsert by organization and name)'
    UNION ALL
    SELECT  80, d.id, 20,
            format(
                '    SELECT count(*), min(id) INTO v_match_count, v_dashboard_%s FROM dashboards WHERE org_id = v_target_org_id AND name = %L; IF v_match_count > 1 THEN RAISE EXCEPTION ''Multiple Viewer dashboards match name %% for org_id=%%'', %L, v_target_org_id; END IF; IF v_match_count = 0 THEN INSERT INTO dashboards (updated_at, created_at, version, org_id, slug, name, user_id, layout, dashboard_filters_enabled, is_archived, is_draft, tags, options) VALUES (%L::timestamptz, %L::timestamptz, %s, v_target_org_id, %L, %L, v_user_id, %L, %L::boolean, %L::boolean, %L::boolean, %s, %L::json) RETURNING id INTO v_dashboard_%s; ELSE UPDATE dashboards SET updated_at = %L::timestamptz, version = %s, slug = %L, user_id = v_user_id, layout = %L, dashboard_filters_enabled = %L::boolean, is_archived = %L::boolean, is_draft = %L::boolean, tags = %s, options = %L::json WHERE id = v_dashboard_%s; END IF;',
                d.id,
                d.name,
                d.name,
                d.updated_at::text,
                d.created_at::text,
                d.version,
                d.slug,
                d.name,
                d.layout,
                d.dashboard_filters_enabled::text,
                d.is_archived::text,
                d.is_draft::text,
                CASE WHEN d.tags IS NULL THEN 'NULL' ELSE format('%L::varchar[]', d.tags::text) END,
                d.options::text,
                d.id,
                d.updated_at::text,
                d.version,
                d.slug,
                d.layout,
                d.dashboard_filters_enabled::text,
                d.is_archived::text,
                d.is_draft::text,
                CASE WHEN d.tags IS NULL THEN 'NULL' ELSE format('%L::varchar[]', d.tags::text) END,
                d.options::text,
                d.id
            )
    FROM selected_dashboards d
    UNION ALL

    /* A dashboard owns its widgets; replacing them makes retries idempotent. */
    SELECT  85, 0, 10, '-- Replace widgets on upserted dashboards'
    UNION ALL
    SELECT  85, d.id, 20,
            format('    DELETE FROM widgets WHERE dashboard_id = v_dashboard_%s;', d.id)
    FROM selected_dashboards d
    UNION ALL

    /* Widgets */
    SELECT  90, 0, 10, '-- Widgets'
    UNION ALL
    SELECT  90, w.id, 20,
            format(
                '    INSERT INTO widgets (updated_at, created_at, visualization_id, text, width, options, dashboard_id) VALUES (%L::timestamptz, %L::timestamptz, %s, %s, %s, %L, v_dashboard_%s) RETURNING id INTO v_widget_%s;',
                w.updated_at::text,
                w.created_at::text,
                CASE WHEN w.visualization_id IS NULL THEN 'NULL' ELSE format('v_visualization_%s', w.visualization_id) END,
                CASE WHEN w.text IS NULL THEN 'NULL' ELSE format('%L', w.text) END,
                w.width,
                w.options,
                w.dashboard_id,
                w.id
            )
    FROM selected_widgets w
    UNION ALL

    /* Redash layout is a JSON array; each grid item identifies its widget in i. */
    SELECT 100, 0, 10, '-- Remap widget identifiers embedded in dashboard layouts'
    UNION ALL
    SELECT 100, w.id, 20,
            format(
                '    UPDATE dashboards SET layout = COALESCE((SELECT jsonb_agg(CASE WHEN jsonb_typeof(layout_item.value) = ''object'' AND layout_item.value ->> ''i'' = %L THEN jsonb_set(layout_item.value, ''{i}'', to_jsonb(v_widget_%s::text)) ELSE layout_item.value END) FROM jsonb_array_elements(layout::jsonb) AS layout_item(value)), ''[]''::jsonb)::text WHERE id = v_dashboard_%s;',
                w.id::text,
                w.id,
                w.dashboard_id
            )
    FROM selected_widgets w
    UNION ALL
    SELECT 110, 0, 10, 'END'
),
body AS (
    SELECT string_agg(line, E'\n' ORDER BY section, entity_id, line_no) AS sql_body
    FROM script
),
delimiter AS (
    /*
      The tag is derived from the complete body. Unlike a fixed $$ delimiter,
      a collision with ordinary SQL, JSON, or text content is computationally
      infeasible. Do not expand this into a generate_series search: that turns
      exporting a small dashboard into one million scans of the generated body.
    */
    SELECT ('$redash_migration_' || md5(b.sql_body) || '$') AS tag
    FROM body b
)
SELECT format(E'DO %s\n%s\n%s;', d.tag, b.sql_body, d.tag) AS sql_script
FROM body b
CROSS JOIN delimiter d;
