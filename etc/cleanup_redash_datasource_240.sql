/*
  Run on the Viewer Redash PostgreSQL database.

  This removes migration artifacts for data_source_id = 240:
    widgets -> dashboards -> visualizations -> queries

  The datasource row itself is intentionally retained.

  Safety contract:
  - Every query currently attached to datasource 240 is treated as a migration
    artifact and will be deleted.
  - A dashboard that contains a datasource-240 widget and a widget from any
    other datasource causes the whole transaction to fail. Review it manually;
    this script will not partially alter a mixed-source dashboard.
  - Text widgets (visualization_id IS NULL) on dashboards selected for removal
    are removed together with those dashboards.

  Change only v_target_datasource_id if a different Viewer datasource must be
  cleaned. Execute as one statement in pgAdmin.
*/
DO $redash_cleanup$
DECLARE
    v_target_datasource_id integer := 240;
    v_dashboard_ids integer[];
    v_deleted_widgets integer := 0;
    v_deleted_dashboards integer := 0;
    v_deleted_visualizations integer := 0;
    v_deleted_queries integer := 0;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM data_sources
        WHERE id = v_target_datasource_id
    ) THEN
        RAISE EXCEPTION 'Viewer datasource id % does not exist', v_target_datasource_id;
    END IF;

    /*
      Keep the affected dashboard IDs before deleting their widgets. A widget
      always belongs to one dashboard according to the supplied DDL.
    */
    SELECT array_agg(DISTINCT d.id ORDER BY d.id)
    INTO v_dashboard_ids
    FROM dashboards d
    JOIN widgets w ON w.dashboard_id = d.id
    JOIN visualizations v ON v.id = w.visualization_id
    JOIN queries q ON q.id = v.query_id
    WHERE q.data_source_id = v_target_datasource_id;

    /* Do not damage a dashboard that also contains a non-target query. */
    IF EXISTS (
        SELECT 1
        FROM dashboards d
        JOIN widgets target_widget ON target_widget.dashboard_id = d.id
        JOIN visualizations target_visualization ON target_visualization.id = target_widget.visualization_id
        JOIN queries target_query ON target_query.id = target_visualization.query_id
        WHERE target_query.data_source_id = v_target_datasource_id
          AND EXISTS (
              SELECT 1
              FROM widgets other_widget
              JOIN visualizations other_visualization ON other_visualization.id = other_widget.visualization_id
              JOIN queries other_query ON other_query.id = other_visualization.query_id
              WHERE other_widget.dashboard_id = d.id
                AND other_query.data_source_id <> v_target_datasource_id
          )
    ) THEN
        RAISE EXCEPTION
            'Cleanup stopped: datasource % is used by a mixed-source dashboard. No records were deleted.',
            v_target_datasource_id;
    END IF;

    IF v_dashboard_ids IS NOT NULL THEN
        DELETE FROM widgets
        WHERE dashboard_id = ANY (v_dashboard_ids);
        GET DIAGNOSTICS v_deleted_widgets = ROW_COUNT;

        DELETE FROM dashboards
        WHERE id = ANY (v_dashboard_ids);
        GET DIAGNOSTICS v_deleted_dashboards = ROW_COUNT;
    END IF;

    DELETE FROM visualizations v
    USING queries q
    WHERE v.query_id = q.id
      AND q.data_source_id = v_target_datasource_id;
    GET DIAGNOSTICS v_deleted_visualizations = ROW_COUNT;

    DELETE FROM queries
    WHERE data_source_id = v_target_datasource_id;
    GET DIAGNOSTICS v_deleted_queries = ROW_COUNT;

    RAISE NOTICE
        'Cleanup completed for datasource %: % widgets, % dashboards, % visualizations, % queries deleted. Datasource row retained.',
        v_target_datasource_id,
        v_deleted_widgets,
        v_deleted_dashboards,
        v_deleted_visualizations,
        v_deleted_queries;
END
$redash_cleanup$;
