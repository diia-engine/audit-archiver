package diia.engine.audit

import diia.engine.audit.dto.PartitionInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.HeadBucketRequest

import java.sql.Connection
import java.sql.DriverManager

class ArchiverJob {
    private static final Logger logger = LoggerFactory.getLogger(ArchiverJob.class)

    static void main(String[] args) {
        logger.info("Initializing Diia Platform Groovy Audit Archiver Service...")

        ArchiverConfig config = new ArchiverConfig()
        config.load()

        logger.info("--- Configuration Loaded ---")
        logger.info("{}", config)
        logger.info("----------------------------")

        S3Client s3Client = S3Client.builder()
                .endpointOverride(URI.create(config.s3Endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.s3KeyId, config.s3Secret)))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()

        try (Connection conn = DriverManager.getConnection(config.jdbcUrl, config.dbUser, config.dbPass)) {
            validateBucketExists(s3Client, config.s3Bucket)

            conn.setAutoCommit(false)

            if (config.isMonolithicMode()) {
                MonolithicArchiver.sliceAndArchive(conn, s3Client, config)
                return
            }

            DatabaseService.runMaintenance(conn)

            List<PartitionInfo> coldPartitions = DatabaseService.getColdPartitions(conn, config.dbSchema, config.retentionHours)

            if (coldPartitions.isEmpty()) {
                logger.info("No cold partitions found for retention threshold ({}h). Exiting.", config.retentionHours)
                return
            }

            for (PartitionInfo part : coldPartitions) {
                String tableName = part.tableName
                String dateSuffix = part.suffix

                String year = dateSuffix.length() >= 4 ? dateSuffix.substring(0, 4) : "0000"
                String month = dateSuffix.length() >= 6 ? dateSuffix.substring(4, 6) : "00"
                String day = dateSuffix.length() >= 8 ? dateSuffix.substring(6, 8) : "00"
                String s3KeyPath = "${year}/${month}/${day}/${tableName}.parquet"

                logger.info("Starting pipeline for partition: {}", tableName)
                boolean isExported = StorageService.exportData(
                        conn, s3Client, config.dbSchema, tableName, null, null, config.s3Bucket, s3KeyPath, AvroSchema.getSchema()
                )

                if (isExported) {
                    DatabaseService.dropPartition(conn, config.dbSchema, tableName)
                    conn.commit()
                } else {
                    logger.error("[{}] Pipeline failed. Skipping DROP. Rolling back transaction.", tableName)
                    conn.rollback()
                }
            }

        } catch (Exception e) {
            logger.error("Fatal exception in ArchiverJob orchestrator: {}", e.getMessage(), e)
            System.exit(1)
        } finally {
            logger.info("Closing S3 client...")
            s3Client.close()
            logger.info("Shutdown completed.")
        }
    }

    private static void validateBucketExists(S3Client s3Client, String bucket) {
        HeadBucketRequest request = (HeadBucketRequest) HeadBucketRequest.builder().bucket(bucket).build()
        s3Client.headBucket(request)
    }
}