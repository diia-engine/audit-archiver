package diia.engine.audit

import diia.engine.audit.dto.TableBoundaries
import software.amazon.awssdk.services.s3.S3Client
import spock.lang.Specification
import java.sql.Connection
import java.time.LocalDate

class MonolithicArchiverSpec extends Specification {

    def "sliceAndArchive should handle empty table"() {
        given:
        def conn = Mock(Connection)
        def s3Client = Mock(S3Client)
        def config = new ArchiverConfig(archiveTableName: "monolith", dbSchema: "public")

        // Гроуві дозволяє мокати статичні методи через GroovyMock, але краще використовувати декомпозицію.
        // Оскільки в нас процедурний стиль, використаємо GroovyMock для DatabaseService.
        GroovySpy(DatabaseService, global: true)
        DatabaseService.getTableBoundaries(conn, "public", "monolith") >> null

        when:
        MonolithicArchiver.sliceAndArchive(conn, s3Client, config)

        then:
        0 * conn.commit()
    }

    def "sliceAndArchive should iterate through days and commit"() {
        given:
        def conn = Mock(Connection)
        def s3Client = Mock(S3Client)
        def config = new ArchiverConfig(
            archiveTableName: "monolith", 
            dbSchema: "public",
            s3Bucket: "test-bucket"
        )

        GroovySpy(DatabaseService, global: true)
        GroovySpy(StorageService, global: true)
        
        def bounds = new TableBoundaries(
            minDate: LocalDate.of(2024, 1, 1),
            maxDate: LocalDate.of(2024, 1, 2) // 2 дні
        )
        
        DatabaseService.getTableBoundaries(conn, "public", "monolith") >> bounds
        StorageService.exportData(*_) >> true

        when:
        MonolithicArchiver.sliceAndArchive(conn, s3Client, config)

        then:
        2 * conn.commit()
    }
}