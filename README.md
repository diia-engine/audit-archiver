# Audit Archiver

Groovy сервіс для архівування "холодних" даних аудиту з PostgreSQL у форматі Parquet у S3 з подальшим видаленням партицій.

## Архітектура

- **audit-archiver** — CronJob, який експортує дані з БД у Parquet → S3
- **Trino** — Query Engine для читання Parquet файлів з S3 (тільки для аудиту)
- **pg_partman** — Використовується тільки для початкового створення partitioned таблиці (одноразово)

## Збірка Docker-образу

```
docker build -t your-registry/audit-archiver:1.0.0 -f Dockerfile .
docker push your-registry/audit-archiver:1.0.0
```
Деплой
1. Створення секретів

 Секрет для PostgreSQL
```
kubectl create secret generic postgres-secret \
  --from-literal=host=your-postgres-host \
  --from-literal=port=5432 \
  --from-literal=user=your-user \
  --from-literal=password=your-password \
  -n edu-dev
```
 Секрет для S3
```
kubectl create secret generic s3-test \
  --from-literal=AccessKey=your-access-key \
  --from-literal=SecretAccessKey=your-secret-key \
  -n edu-dev
```
2. Деплой audit-archiver
```
helm install audit-archiver ./helm \
  --namespace edu-dev \
  --create-namespace
```
3. Деплой Trino
```
helm install trino-audit-release ./trino/helm \
  --namespace edu-dev \
  --create-namespace
```
Локальний запуск

Дивіться папку local-env/.
