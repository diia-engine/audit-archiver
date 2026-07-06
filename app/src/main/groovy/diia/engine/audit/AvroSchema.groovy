package diia.engine.audit

import org.apache.avro.Schema

class AvroSchema {
    // Сувора Avro-схема. Всі поля union ["null", "type"] для захисту від NPE при читаннях з БД
    private static final String AVRO_SCHEMA_JSON = """{
        "type": "record",
        "name": "AuditEventMaster",
        "namespace": "diia.audit",
        "fields": [
            {"name": "id", "type": ["null", "string"], "default": null},
            {"name": "request_id", "type": ["null", "string"], "default": null},
            {"name": "application_name", "type": ["null", "string"], "default": null},
            {"name": "name", "type": ["null", "string"], "default": null},
            {"name": "type", "type": ["null", "string"], "default": null},
            {"name": "timestamp", "type": ["null", {"type": "long", "logicalType": "timestamp-micros"}], "default": null},
            {"name": "user_keycloak_id", "type": ["null", "string"], "default": null},
            {"name": "user_name", "type": ["null", "string"], "default": null},
            {"name": "user_drfo", "type": ["null", "string"], "default": null},
            {"name": "source_system", "type": ["null", "string"], "default": null},
            {"name": "source_application", "type": ["null", "string"], "default": null},
            {"name": "source_business_process", "type": ["null", "string"], "default": null},
            {"name": "source_business_process_definition_id", "type": ["null", "string"], "default": null},
            {"name": "source_business_process_instance_id", "type": ["null", "string"], "default": null},
            {"name": "source_business_activity", "type": ["null", "string"], "default": null},
            {"name": "source_business_activity_id", "type": ["null", "string"], "default": null},
            {"name": "context", "type": ["null", "string"], "default": null},
            {"name": "received", "type": ["null", {"type": "long", "logicalType": "timestamp-micros"}], "default": null}
        ]
    }"""

    static Schema getSchema() {
        return new Schema.Parser().parse(AVRO_SCHEMA_JSON)
    }
}