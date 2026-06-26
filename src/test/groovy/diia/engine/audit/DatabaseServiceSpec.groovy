package diia.engine.audit

import spock.lang.Specification
import java.sql.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DatabaseServiceSpec extends Specification {

    private static final DateTimeFormatter DAILY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private static final DateTimeFormatter HOURLY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

    def "getColdPartitions should return empty list if no tables match"() {
        given:
        def conn = Mock(Connection)
        def stmt = Mock(PreparedStatement)
        def rs = Mock(ResultSet)

        conn.prepareStatement(_ as String) >> stmt
        stmt.executeQuery() >> rs
        rs.next() >> false

        when:
        def result = DatabaseService.getColdPartitions(conn, "public", 24)

        then:
        result.isEmpty()
    }

    def "getColdPartitions should filter daily and hourly partitions correctly"() {
        given:
        def conn = Mock(Connection)
        def stmt = Mock(PreparedStatement)
        def rs = Mock(ResultSet)
        
        // Retention: 24h
        def now = LocalDateTime.now()
        def coldDaily = now.minusDays(2).format(DAILY_FORMAT)      // 48h ago (COLD)
        def hotHourly = now.minusMinutes(30).format(HOURLY_FORMAT) // 30m ago (HOT)
        def coldHourly = now.minusHours(25).format(HOURLY_FORMAT)  // 25h ago (COLD)
        
        conn.prepareStatement(_ as String) >> stmt
        stmt.executeQuery() >> rs
        
        rs.next() >>> [true, true, true, false]
        rs.getString(1) >>> [
            "audit_event_master_p${coldDaily}",
            "audit_event_master_p${hotHourly}",
            "audit_event_master_p${coldHourly}"
        ]

        when:
        def result = DatabaseService.getColdPartitions(conn, "public", 24)

        then:
        result.size() == 2
        result.any { it.tableName.endsWith(coldDaily) }
        result.any { it.tableName.endsWith(coldHourly) }
        !result.any { it.tableName.endsWith(hotHourly) }
    }

    def "dropPartition should generate correct SQL with quotes"() {
        given:
        def conn = Mock(Connection)
        def stmt = Mock(Statement)
        conn.createStatement() >> stmt

        when:
        DatabaseService.dropPartition(conn, "my_schema", "my_table")

        then:
        1 * stmt.execute('DROP TABLE IF EXISTS "my_schema"."my_table"')
    }

    def "getTableBoundaries should return null if table is empty"() {
        given:
        def conn = Mock(Connection)
        def stmt = Mock(Statement)
        def rs = Mock(ResultSet)
        
        conn.createStatement() >> stmt
        stmt.executeQuery(_) >> rs
        rs.next() >> true
        rs.getTimestamp(1) >> null

        when:
        def result = DatabaseService.getTableBoundaries(conn, "public", "test")

        then:
        result == null
    }

    def "getTableBoundaries should return correct boundaries"() {
        given:
        def conn = Mock(Connection)
        def stmt = Mock(Statement)
        def rs = Mock(ResultSet)
        
        def minTs = Timestamp.valueOf("2024-01-01 10:00:00")
        def maxTs = Timestamp.valueOf("2024-01-05 15:00:00")

        conn.createStatement() >> stmt
        stmt.executeQuery(_) >> rs
        rs.next() >> true
        rs.getTimestamp(1) >> minTs
        rs.getTimestamp(2) >> maxTs

        when:
        def result = DatabaseService.getTableBoundaries(conn, "public", "test")

        then:
        result.minDate == LocalDate.of(2024, 1, 1)
        result.maxDate == LocalDate.of(2024, 1, 5)
    }
}