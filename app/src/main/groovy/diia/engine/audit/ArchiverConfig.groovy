package diia.engine.audit

import java.util.regex.Pattern

class ArchiverConfig {
    private static final Pattern TABLE_NAME_PATTERN = ~/^[a-zA-Z0-9_]+$/
    String dbHost
    String dbPort
    String dbName
    String dbSchema
    String dbUser
    String dbPass
    String s3Endpoint
    String s3KeyId
    String s3Secret
    String s3Bucket
    int retentionHours
    String jdbcUrl
    String archiveTableName

    def load() {
        dbHost = System.getenv().getOrDefault("DB_HOST", "localhost")
        dbPort = System.getenv().getOrDefault("DB_PORT", "5454")
        dbName = System.getenv().getOrDefault("DB_NAME", "audit")
        dbSchema = System.getenv().getOrDefault("DB_SCHEMA", "public")
        dbUser = System.getenv().getOrDefault("DB_USER", "postgres")
        dbPass = System.getenv().getOrDefault("DB_PASSWORD", "postgres_password")

        s3Endpoint = System.getenv().getOrDefault("S3_ENDPOINT", "http://localhost:9000")
        if (!s3Endpoint.startsWith("http://") && !s3Endpoint.startsWith("https://")) {
            s3Endpoint = "http://" + s3Endpoint
        }
        s3KeyId = System.getenv().getOrDefault("S3_ACCESS_KEY", "minio_admin")
        s3Secret = System.getenv().getOrDefault("S3_SECRET_KEY", "minio_password")
        s3Bucket = System.getenv().getOrDefault("S3_BUCKET", "s3-aikom-test")

        retentionHours = parseRetentionHours(System.getenv().getOrDefault("RETENTION_HOURS", "24"))

        jdbcUrl = "jdbc:postgresql://${dbHost}:${dbPort}/${dbName}"

        archiveTableName = System.getenv().getOrDefault("ARCHIVE_TABLE_NAME", "").trim()

        validate()
    }

    private int parseRetentionHours(String value) {
        try {
            return Integer.parseInt(value)
        } catch (Exception e) {
            throw new IllegalArgumentException("RETENTION_HOURS must be integer", e)
        }
    }

    private void validate() {
        if (retentionHours <= 0) {
            throw new IllegalArgumentException("RETENTION_HOURS must be > 0")
        }

        if (archiveTableName && !TABLE_NAME_PATTERN.matcher(archiveTableName).matches()) {
            throw new IllegalArgumentException("Invalid ARCHIVE_TABLE_NAME: ${archiveTableName}")
        }
        
        if (!TABLE_NAME_PATTERN.matcher(dbSchema).matches()) {
            throw new IllegalArgumentException("Invalid DB_SCHEMA name")
        }
    }

    boolean isMonolithicMode() {
        return archiveTableName != null && !archiveTableName.isEmpty()
    }

    private String mask(String value) {
        if (value == null || value.length() < 4) return "***"
        return value.substring(0, 3) + "***" + value.substring(value.length() - 1)
    }

    @Override
    String toString() {
        return "ArchiverConfig{" +
                "dbHost='" + dbHost + '\'' +
                ", dbPort='" + dbPort + '\'' +
                ", dbName='" + dbName + '\'' +
                ", dbSchema='" + dbSchema + '\'' +
                ", dbUser='" + dbUser + '\'' +
                ", dbPass='" + mask(dbPass) + '\'' +
                ", s3Endpoint='" + s3Endpoint + '\'' +
                ", s3KeyId='" + mask(s3KeyId) + '\'' +
                ", s3Secret='" + mask(s3Secret) + '\'' +
                ", s3Bucket='" + s3Bucket + '\'' +
                ", retentionHours=" + retentionHours +
                ", archiveTableName='" + archiveTableName + '\'' +
                '}'
    }
}
