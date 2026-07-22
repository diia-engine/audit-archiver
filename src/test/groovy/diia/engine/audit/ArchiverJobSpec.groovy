package diia.engine.audit

import diia.engine.audit.dto.PartitionInfo
import spock.lang.Specification

import java.time.LocalDateTime

class ArchiverJobSpec extends Specification {

    def "buildS3KeyPath uses the parsed date regardless of partition suffix format"() {
        given:
        def partition = new PartitionInfo(
                tableName: tableName,
                suffix: suffix,
                partitionDate: partitionDate
        )

        expect:
        ArchiverJob.buildS3KeyPath(partition) == expectedKey

        where:
        tableName                                      | suffix            | partitionDate                          || expectedKey
        "audit_event_master_p20260718"                 | "20260718"       | LocalDateTime.of(2026, 7, 18, 0, 0)    || "2026/07/18/audit_event_master_p20260718.parquet"
        "audit_event_master_p2026_07_18"              | "2026_07_18"     | LocalDateTime.of(2026, 7, 18, 0, 0)    || "2026/07/18/audit_event_master_p2026_07_18.parquet"
        "audit_event_master_p20260718_1345"           | "20260718_1345"  | LocalDateTime.of(2026, 7, 18, 13, 45)  || "2026/07/18/audit_event_master_p20260718_1345.parquet"
        "custom_audit_events_p2026_07_18"             | "2026_07_18"     | LocalDateTime.of(2026, 7, 18, 0, 0)    || "2026/07/18/custom_audit_events_p2026_07_18.parquet"
    }
}
