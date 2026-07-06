package diia.engine.audit.dto

import java.time.LocalDateTime

class PartitionInfo {
    String tableName
    String suffix
    LocalDateTime partitionDate
}
