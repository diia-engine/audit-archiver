package diia.engine.audit


import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import spock.lang.Specification

import java.sql.*
import java.time.LocalDateTime

class StorageServiceSpec extends Specification {

    def "exportData should return true and skip upload if no records found"() {
        given:
        def conn = Mock(Connection)
        def s3Client = Mock(S3Client)
        def stmt = Mock(PreparedStatement)
        def rs = Mock(ResultSet)
        def schema = AvroSchema.getSchema()

        conn.prepareStatement(_ as String) >> stmt
        stmt.executeQuery() >> rs
        rs.next() >> false

        when:
        def result = StorageService.exportData(conn, s3Client, "public", "test", null, null, "bucket", "key", schema)

        then:
        result == true
        0 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody)
    }

    def "exportData should handle successful export and upload"() {
        given:
        def conn = Mock(Connection)
        def s3Client = Mock(S3Client)
        def stmt = Mock(PreparedStatement)
        def rs = Mock(ResultSet)
        def schema = AvroSchema.getSchema()
        
        conn.prepareStatement(_ as String) >> stmt
        stmt.executeQuery() >> rs
        rs.next() >>> [true, false] // Одна строка
        rs.getString(_) >> "test-value"
        rs.getTimestamp(_) >> Timestamp.valueOf(LocalDateTime.now())

        def putResponse = PutObjectResponse.builder().eTag("test-etag").build()
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> putResponse

        when:
        def result = StorageService.exportData(conn, s3Client, "public", "test", null, null, "bucket", "key", schema)

        then:
        result == true
    }

    def "exportData should return false on exception"() {
        given:
        def conn = Mock(Connection)
        conn.prepareStatement(_) >> { throw new SQLException("DB Error") }

        when:
        def result = StorageService.exportData(conn, null, "public", "test", null, null, "bucket", "key", null)

        then:
        result == false
    }
}