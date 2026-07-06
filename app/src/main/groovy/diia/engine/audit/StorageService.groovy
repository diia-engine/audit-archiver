package diia.engine.audit

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import org.apache.hadoop.fs.Path as HadoopPath

import java.nio.file.Files
import java.nio.file.Path
import java.sql.*
import java.time.LocalDateTime

class StorageService {
    private static final Logger logger = LoggerFactory.getLogger(StorageService.class)

    /**
     * Універсальний метод експорту даних в Parquet та S3.
     */
    static boolean exportData(Connection conn, S3Client s3Client, String dbSchema, String tableName,
                              LocalDateTime start, LocalDateTime end,
                              String bucket, String s3KeyPath, Schema schema) {
        long startTime = System.currentTimeMillis()
        
        // Створюємо унікальний шлях через createTempFile
        Path tempPath = Files.createTempFile("audit-${tableName}-", ".parquet")
        // Видаляємо порожній файл, бо Hadoop FS хоче створити його самостійно
        Files.deleteIfExists(tempPath)
        
        File tempFile = tempPath.toFile()
        int fetchSize = 25_000
        long rowsCount = 0

        try {
            String sql = """
                SELECT id, request_id, application_name, name, type, timestamp, 
                       user_keycloak_id, user_name, user_drfo, 
                       source_system, source_application, source_business_process, 
                       source_business_process_definition_id, source_business_process_instance_id, 
                       source_business_activity, source_business_activity_id, 
                       context, received 
                FROM "${dbSchema}"."${tableName}"
            """
            if (start != null && end != null) {
                sql += " WHERE timestamp >= ? AND timestamp < ?"
            }

            try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(new HadoopPath(tempFile.absolutePath))
                    .withSchema(schema)
                    .withCompressionCodec(CompressionCodecName.SNAPPY)
                    .withRowGroupSize(128 * 1024 * 1024)
                    .build();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setFetchSize(fetchSize)

                if (start != null && end != null) {
                    stmt.setTimestamp(1, Timestamp.valueOf(start))
                    stmt.setTimestamp(2, Timestamp.valueOf(end))
                }

                GenericRecord record = new GenericData.Record(schema)

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        record.put("id", rs.getString("id"))
                        record.put("request_id", rs.getString("request_id"))
                        record.put("application_name", rs.getString("application_name"))
                        record.put("name", rs.getString("name"))
                        record.put("type", rs.getString("type"))

                        record.put("timestamp", toMicros(rs.getTimestamp("timestamp")))

                        record.put("user_keycloak_id", rs.getString("user_keycloak_id"))
                        record.put("user_name", rs.getString("user_name"))
                        record.put("user_drfo", rs.getString("user_drfo"))
                        
                        record.put("source_system", rs.getString("source_system"))
                        record.put("source_application", rs.getString("source_application"))
                        record.put("source_business_process", rs.getString("source_business_process"))
                        record.put("source_business_process_definition_id", rs.getString("source_business_process_definition_id"))
                        record.put("source_business_process_instance_id", rs.getString("source_business_process_instance_id"))
                        record.put("source_business_activity", rs.getString("source_business_activity"))
                        record.put("source_business_activity_id", rs.getString("source_business_activity_id"))
                        
                        record.put("context", rs.getString("context"))
                        record.put("received", toMicros(rs.getTimestamp("received")))

                        writer.write(record)
                        rowsCount++

                        if (rowsCount % 100_000 == 0) {
                            logger.info("[{}] Processed {} rows...", tableName, rowsCount)
                        }
                    }
                }
            }

            if (rowsCount == 0) {
                logger.info("[{}] No records found. Skipping upload.", tableName)
                return true
            }

            double sizeMb = tempFile.length() / (1024.0 * 1024.0)
            logger.info("[{}] Parquet built: {} MB ({} rows). Uploading to s3://{}/{} ...",
                    tableName, String.format("%.2f", sizeMb), rowsCount, bucket, s3KeyPath)

            long s3StartTime = System.currentTimeMillis()
            
            PutObjectRequest putObjectRequest = (PutObjectRequest) PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3KeyPath)
                    .build()
                    
            PutObjectResponse response = s3Client.putObject(putObjectRequest, RequestBody.fromFile(tempFile))
            
            long s3Duration = System.currentTimeMillis() - s3StartTime
            logger.info("[{}] Export complete! S3 ETag: {} | DB stream duration: {}ms | S3 duration: {}ms", 
                    tableName, response.eTag(), System.currentTimeMillis() - startTime - s3Duration, s3Duration)
            
            return true

        } catch (Exception e) {
            logger.error("[{}] Pipeline failed: {}", tableName, e.getMessage(), e)
            return false
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }

    private static Long toMicros(Timestamp ts) {
        if (ts == null) return null
        return (ts.getTime() * 1000L) + ((ts.getNanos() % 1_000_000) / 1000L)
    }
}