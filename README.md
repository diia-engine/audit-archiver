# Audit Archiver

`audit-archiver` is a Groovy batch service that moves cold audit data from PostgreSQL to Apache Parquet files in S3-compatible object storage. In the standard mode, it exports complete PostgreSQL partitions and removes each partition only after its upload succeeds. The archived files can then be queried through Trino.

## How it works

The standard workflow is designed for the partitioned `public.audit_event_master` table:

1. The job runs `pg_partman` maintenance (a failure is logged but does not stop the job).
2. It finds tables named `audit_event_master_p*` in the configured schema.
3. It considers a partition cold when the timestamp encoded in its name is older than `RETENTION_HOURS`.
4. It streams the partition into a Snappy-compressed Parquet file in `/tmp`.
5. It uploads the file to S3 under `YYYY/MM/DD/<partition-name>.parquet`.
6. After a successful upload, it drops the source partition and commits the database transaction. A failed export rolls the transaction back and leaves the partition in PostgreSQL.

The job first verifies that the target S3 bucket exists. It does not create buckets.

### Components

- **Audit Archiver** — a Kubernetes CronJob (or a locally executed Gradle application) that performs the export.
- **PostgreSQL and pg_partman** — PostgreSQL stores the active data; pg_partman creates and maintains daily partitions.
- **S3-compatible storage** — stores the generated Parquet files. MinIO is used by the local environment.
- **Trino** — reads the Parquet archive for audit and analytics use cases.

## Prerequisites

- Java 21 for a local Gradle build or run.
- Docker for image builds and the local environment.
- Kubernetes and Helm 3 for deployment.
- Access to the PostgreSQL database, the S3 bucket, and, for the supplied Helm charts, the S3 CA certificate.

The database user must be able to read the source data, run `partman.run_maintenance()` when using the partitioned mode, and drop exported partitions. Apply the database setup scripts with an appropriately privileged PostgreSQL role.

## Database preparation

> **Important:** `sql/01-create-partitions.sql` renames the existing `public.audit_event` table to `audit_event_archive`, creates a new partitioned `public.audit_event_master` table, and creates `public.audit_event` as a view. Review and test this migration before applying it to a production database.

1. Tune the Kafka connector to write to the new layout. See [etc/KafkaConnector.yaml](etc/KafkaConnector.yaml).
2. Initialize `pg_partman` and create the partitioned table:

   ```bash
   psql -d audit -f sql/01-create-partitions.sql
   ```

3. Apply grants and transfer ownership of existing partitions as needed:

   ```bash
   psql -d audit -f sql/02-db-grants.sql
   ```

The partition script configures native daily partitions and pre-creates 14 future partitions. The archiver accepts partition suffixes in `yyyyMMdd`, `yyyy_MM_dd`, and `yyyyMMdd_HHmm` formats.

## Configuration

The application is configured with environment variables. Values shown below are the built-in defaults, intended only for the local environment.

| Variable | Default | Description |
| --- | --- | --- |
| `DB_HOST` | `localhost` | PostgreSQL host. |
| `DB_PORT` | `5454` | PostgreSQL port. |
| `DB_NAME` | `audit` | Database name. |
| `DB_SCHEMA` | `public` | Schema containing the source tables. Only letters, numbers, and underscores are accepted. |
| `DB_USER` | `postgres` | PostgreSQL user. |
| `DB_PASSWORD` | `postgres_password` | PostgreSQL password. |
| `S3_ENDPOINT` | `http://s3.krrt-ncr.loc:9000` | S3-compatible endpoint. `http://` is added when no scheme is supplied. |
| `S3_ACCESS_KEY` | `minio_admin` | S3 access key. |
| `S3_SECRET_KEY` | `minio_password` | S3 secret key. |
| `S3_BUCKET` | `s3-aikom-test` | Existing target bucket. |
| `RETENTION_HOURS` | `24` | Positive number of hours before a partition is eligible for archiving. |
| `ARCHIVE_TABLE_NAME` | empty | Enables monolithic mode for the specified table. Only letters, numbers, and underscores are accepted. |

`S3_REGION` is passed by the Helm chart, but the current application creates its S3 client with `us-east-1`; it does not read this variable.

### Monolithic mode

Set `ARCHIVE_TABLE_NAME` to export an existing non-partitioned table by daily timestamp ranges. The job writes one file per day to `YYYY/MM/DD/<table-name>.parquet`. This mode does **not** delete source rows or the source table, and `RETENTION_HOURS` is not used to limit the exported date range. It is useful for a one-off migration of the `audit_event_archive` table created by the database migration.

## Build and test

Run the test suite:

```bash
./gradlew test
```

Build the executable fat JAR:

```bash
./gradlew shadowJar
```

The artifact is written to `build/libs/`.

Build and push a container image:

```bash
docker build -t your-registry/audit-archiver:1.0.0 -f Dockerfile .
docker push your-registry/audit-archiver:1.0.0
```

> **Current repository note:** `build.gradle` configures the fat JAR without an `-all` classifier, while the Dockerfile copies `build/libs/*-all.jar`. Align one of these patterns before relying on the Docker build in CI.

## Local environment

The Compose setup starts PostgreSQL with pg_partman, MinIO, Trino, and Redash. It does not start the archiver container; run the application from Gradle after the services are ready.

```bash
docker compose -f local-env/docker-compose-ext.yaml up -d
./gradlew run
```

The default application values connect to the local PostgreSQL instance on port `5454`. Before running the archiver, create the `s3-aikom-test` bucket in the MinIO console at `http://localhost:9001` (or change `S3_BUCKET` to an existing bucket). Trino is exposed at `http://localhost:8080`, and Redash at `http://localhost:5000`.

To stop the local environment while retaining named volumes:

```bash
docker compose -f local-env/docker-compose-ext.yaml down
```

## Kubernetes deployment

The Helm charts are located under `deploy-templates/`:

- `deploy-templates/audit-cronjob` deploys the archiver CronJob and optionally a manual migration Job.
- `deploy-templates/trino` deploys Trino and can initialize its external Parquet table.

Review and customize the relevant `values.yaml` before installation, especially image references, database host and secret keys, S3 endpoint and bucket, resource limits, and the schedule.

### Required secrets and CA ConfigMap

The supplied values expect existing Kubernetes secrets rather than creating them. For example:

```bash
kubectl create namespace edu-dev

kubectl create secret generic citus-roles-secrets \
  --from-literal=anSvcName=your-db-user \
  --from-literal=anSvcPass=your-db-password \
  --namespace edu-dev

kubectl create secret generic s3-test \
  --from-literal=AccessKey=your-access-key \
  --from-literal=SecretAccessKey=your-secret-key \
  --namespace edu-dev

kubectl create configmap audit-archiver-s3-ca \
  --from-file=s3-lb.pem=/path/to/s3-lb.pem \
  --namespace edu-dev
```

Both charts mount `audit-archiver-s3-ca` and import `s3-lb.pem` into a Java truststore. If the endpoint uses a certificate already trusted by the base image, you may adapt the chart to omit this custom truststore; do not simply omit the ConfigMap while using the provided templates.

### Install the archiver

```bash
helm install audit-archiver ./deploy-templates/audit-cronjob/   
  --namespace edu-dev
```

Check scheduled and completed jobs:

```bash
kubectl get cronjobs,jobs --namespace edu-dev
kubectl get jobs --namespace edu-dev --watch
```

The default schedule in `deploy-templates/audit-cronjob/values.yaml` is `45 13 * * *`. Although the values file contains `timeZone: Europe/Kyiv`, the current CronJob template does not render `spec.timeZone`; scheduling therefore follows the Kubernetes controller's configured time zone. Add `timeZone` to the template if Kyiv time is required.

To run a one-off monolithic migration, set `manualJob.enabled: true` and `manualJob.archiveTableName` and then run the following command. The generated Job is named `audit-archiver-cronjob-manual`.

```bash
helm template audit-archiver-manual ./deploy-templates/audit-cronjob/ --namespace edu-dev --show-only templates/job-manual.yaml --set manualJob.enabled=true | oc create -f -
```

### Install Trino

```bash
helm install trino-audit-release ./deploy-templates/trino \
  --namespace edu-dev \
  --values ./deploy-templates/trino/values.yaml
```

The chart exposes a Hive catalog named `minio`. Its built-in initialization creates `minio.audit.events`, which reads Parquet files from the configured archive bucket. Set `init.enabled: true` to create the dedicated initialization Job. The Deployment also contains a post-start initialization routine; verify the resulting table after installation:

```bash
kubectl get pods --namespace edu-dev -l app.kubernetes.io/name=trino
kubectl get svc --namespace edu-dev
```

## Optional: migrate Redash queries and dashboards

After Trino and the `minio.audit.events` table are available, you can migrate the audit queries and dashboards from a Redash Admin instance to a Redash Viewer instance.

### 1. Create the Viewer data source

Sign in to Redash Viewer with an Officer-realm user that has the `admin` role. Open **Settings → New Data Source**, select **Trino**, and use the following settings:

| Field | Value |
| --- | --- |
| Name | `s3-audit` |
| Protocol | `http` |
| Host | `trino-audit-trino` for the installation command above; otherwise, `<trino-release-name>-trino` |
| Port | `8080` |
| Username | `admin` |
| Password | Leave empty |
| Catalog | `minio` |
| Schema | `audit` |

The data-source name and type must remain `s3-audit` and `trino`: the migration scripts use these values to locate the Viewer data source.

### 2. Import the supplied example artifacts

To import the example queries and dashboards, execute [sql/redash-artifacts-example.sql](sql/redash-artifacts-example.sql) against the **Redash Viewer PostgreSQL database**. The script expects the target user and organization IDs to be `1`; update `v_user_id` and `v_target_org_id` in the script when the Viewer instance uses different IDs.

### 3. Generate and import a custom migration

To migrate artifacts from a Redash Admin instance:

1. Review the `config` CTE at the top of [sql/03-redash-export.sql](sql/03-redash-export.sql). Set the target Viewer organization and user IDs, and optionally restrict the export with dashboard slugs or query names.
2. Execute the script against the **Redash Admin PostgreSQL database**. It returns one `sql_script` value.
3. Copy that value to a UTF-8 `.sql` file and execute it against the **Redash Viewer PostgreSQL database**.

The generated script upserts queries and dashboards, replaces their widgets, and validates that the required Viewer data source and user exist. It skips mixed-data-source dashboards to avoid importing incomplete dashboards. Treat the generated SQL as sensitive operational data and review it before execution.

## Archive layout and querying

For a partition named `audit_event_master_p20260728`, the standard mode uploads:

```text
s3://<bucket>/2026/07/28/audit_event_master_p20260728.parquet
```

The Parquet schema contains the audit-event fields, including `id`, `request_id`, `application_name`, `name`, `type`, `timestamp`, user and source metadata, `context`, and `received`. Example Trino query:

```sql
SELECT date_trunc('day', "timestamp") AS day, count(*) AS events
FROM minio.audit.events
GROUP BY 1
ORDER BY 1 DESC;
```

## Operational considerations

- The export is a two-phase operational process: the Parquet object is uploaded before the source partition is dropped. If a failure occurs after a successful upload but before a committed drop, a retry can overwrite the same S3 key.
- Empty partitions are treated as successfully exported and are dropped, but no Parquet object is created.
- The temporary Parquet file is written to `/tmp`; size `tmpStorage.sizeLimit` and ephemeral-storage limits for the largest partition you expect to export.
- Use the supplied Redash queries under `redash-queries/` as starting points for archive analysis.
