package diia.engine.audit

import diia.engine.audit.dto.TableBoundaries
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.s3.S3Client
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class MonolithicArchiver {
    private static final Logger logger = LoggerFactory.getLogger(MonolithicArchiver.class)

    static void sliceAndArchive(Connection conn, S3Client s3Client, ArchiverConfig config) {
        String tableName = config.archiveTableName
        logger.info("=== STARTING MONOLITHIC SLICING FOR TABLE: {} ===", tableName)

        TableBoundaries bounds = DatabaseService.getTableBoundaries(conn, config.dbSchema, tableName)
        if (bounds == null) {
            logger.warn("Table {} is empty or missing timestamps. Exiting.", tableName)
            return
        }

        LocalDate currentDate = bounds.minDate
        LocalDate maxDate = bounds.maxDate

        int totalDays = ChronoUnit.DAYS.between(currentDate, maxDate) + 1
        int dayCounter = 0

        while (!currentDate.isAfter(maxDate)) {
            dayCounter++
            LocalDateTime startOfDay = currentDate.atStartOfDay()
            LocalDateTime endOfDay = currentDate.plusDays(1).atStartOfDay()

            String year = String.format("%04d", currentDate.getYear())
            String month = String.format("%02d", currentDate.getMonthValue())
            String day = String.format("%02d", currentDate.getDayOfMonth())

            String s3KeyPath = "${year}/${month}/${day}/${tableName}.parquet"

            logger.info("Progress [{}/{}] -> Processing day: {}", dayCounter, totalDays, currentDate)

            boolean success = StorageService.exportData(
                    conn, s3Client, config.dbSchema, tableName, startOfDay, endOfDay,
                    config.s3Bucket, s3KeyPath, AvroSchema.getSchema()
            )

            if (success) {
                conn.commit() 
            } else {
                logger.error("!!! SLICING HALTED on day {}. Fix the issue and restart.", currentDate)
                conn.rollback()
                System.exit(1)
            }

            currentDate = currentDate.plusDays(1)
        }

        logger.info("=== MONOLITHIC EXPORT SUCCESSFULLY COMPLETED FOR {} DAYS ===", dayCounter)
    }
}