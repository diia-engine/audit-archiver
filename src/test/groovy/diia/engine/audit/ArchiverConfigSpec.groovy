package diia.engine.audit

import spock.lang.Specification
import spock.lang.Unroll

class ArchiverConfigSpec extends Specification {

    def "load() should set default values when environment variables are not present"() {
        given:
        def config = new ArchiverConfig()

        when:
        config.load([:])

        then:
        config.dbHost == "localhost"
        config.dbPort == "5454"
        config.dbName == "audit"
        config.dbSchema == "public"
        config.dbUser == "postgres"
        config.dbPass == "postgres_password"
        config.s3Endpoint == "http://s3.krrt-ncr.loc:9000"
        config.s3KeyId == "minio_admin"
        config.s3Secret == "minio_password"
        config.s3Bucket == "s3-aikom-test"
        config.retentionHours == 24
        config.jdbcUrl == "jdbc:postgresql://localhost:5454/audit"
        config.archiveTableName == ""
    }

    def "isMonolithicMode() should return true when archiveTableName is set"() {
        given:
        def config = new ArchiverConfig()
        config.archiveTableName = "some_table"

        expect:
        config.isMonolithicMode()
    }

    @Unroll
    def "toString() should mask sensitive information"() {
        given:
        def config = new ArchiverConfig(
            dbPass: dbPass,
            s3KeyId: s3KeyId,
            s3Secret: s3Secret
        )

        expect:
        def result = config.toString()
        result.contains("dbPass='${maskedPass}'")
        result.contains("s3KeyId='${maskedKey}'")
        result.contains("s3Secret='${maskedSecret}'")

        where:
        dbPass         | s3KeyId      | s3Secret     || maskedPass | maskedKey | maskedSecret
        "password123"  | "keyid1234"  | "secret123"  || "pas***3"  | "key***4" | "sec***3"
        "123"          | "abc"        | "xy"         || "***"      | "***"     | "***"
    }

    def "validate() should throw exception for invalid archive table name"() {
        given:
        def config = new ArchiverConfig()
        config.archiveTableName = "invalid-name!"

        when:
        config.validate()

        then:
        thrown(IllegalArgumentException)
    }
}
