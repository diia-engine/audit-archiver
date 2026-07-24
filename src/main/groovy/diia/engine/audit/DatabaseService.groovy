package diia.engine.audit

import diia.engine.audit.dto.PartitionInfo
import diia.engine.audit.dto.TableBoundaries
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class)
    
    private static final DateTimeFormatter DAILY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private static final DateTimeFormatter UNDERSCORE_DAILY_FORMAT = DateTimeFormatter.ofPattern("yyyy_MM_dd")
    private static final DateTimeFormatter HOURLY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

    static void runMaintenance(Connection conn) {
        logger.info("Executing pg_partman maintenance...")
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT partman.run_maintenance();")
        } catch (Exception e) {
            logger.warn("pg_partman maintenance failed: {}", e.getMessage())
        }
    }

    static List<PartitionInfo> getColdPartitions(Connection conn, String schema, int retentionHours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours)
        List<PartitionInfo> coldTables = []
        
        String sql = "SELECT tablename FROM pg_tables WHERE schemaname = ? AND tablename LIKE 'audit_event_master_p%' ORDER BY tablename"

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schema)
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString(1)
                    String suffix = tableName.split('_p').last()
                    
                    try {
                        LocalDateTime partDate
                        if (suffix ==~ /\d{8}_\d{4}/) {
                            partDate = LocalDateTime.parse(suffix, HOURLY_FORMAT)
                        } else if (suffix ==~ /\d{4}_\d{2}_\d{2}/) {
                            partDate = LocalDate.parse(suffix, UNDERSCORE_DAILY_FORMAT).atStartOfDay()
                        } else {
                            partDate = LocalDate.parse(suffix, DAILY_FORMAT).atStartOfDay()
                        }

                        if (partDate.isBefore(cutoff)) {
                            coldTables.add(new PartitionInfo(tableName: tableName, suffix: suffix, partitionDate: partDate))
                        }
                    } catch (Exception e) {
                        logger.warn("Could not parse partition suffix {}: {}", suffix, e.message)
                    }
                }
            }
        }
        return coldTables
    }

    static void dropPartition(Connection conn, String schema, String tableName) throws SQLException {
        logger.info("[{}] Dropping table...", tableName)
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS \"${schema}\".\"${tableName}\"")
        }
    }

    static TableBoundaries getTableBoundaries(Connection conn, String schema, String tableName) {
        String sql = "SELECT min(timestamp), max(timestamp) FROM \"${schema}\".\"${tableName}\""
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next() && rs.getTimestamp(1) != null) {
                return new TableBoundaries(
                    minDate: rs.getTimestamp(1).toLocalDateTime().toLocalDate(),
                    maxDate: rs.getTimestamp(2).toLocalDateTime().toLocalDate()
                )
            }
        }
        return null
    }
}
