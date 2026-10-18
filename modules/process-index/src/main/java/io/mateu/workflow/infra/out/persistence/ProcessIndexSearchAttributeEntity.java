package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.search.SearchAttributeType;
import io.mateu.workflow.search.SearchAttributeValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One search attribute of a process in the process index (V5 of the read database, V48 of the engine's):
 * the value sits in the one column its type uses. Written by the projector with the index row, read
 * only through {@code EXISTS} conditions (see {@code SearchAttributeConditions}).
 */
@Entity
@Table(name = "process_index_search_attribute", indexes = {
        @Index(name = "idx_pisa_keyword", columnList = "attr_name, keyword_value"),
        @Index(name = "idx_pisa_number", columnList = "attr_name, number_value"),
        @Index(name = "idx_pisa_date", columnList = "attr_name, date_value")
})
@IdClass(ProcessIndexSearchAttributeEntity.Key.class)
public class ProcessIndexSearchAttributeEntity {

    @Id
    @Column(name = "process_id", length = 255)
    private String processId;

    @Id
    @Column(name = "attr_name", length = 64)
    private String attrName;

    @Column(name = "attr_type", length = 16, nullable = false)
    private String attrType;

    @Column(name = "keyword_value", length = 255)
    private String keywordValue;

    @Column(name = "number_value")
    private Double numberValue;

    @Column(name = "date_value")
    private LocalDateTime dateValue;

    protected ProcessIndexSearchAttributeEntity() {
    }

    public ProcessIndexSearchAttributeEntity(String processId, SearchAttributeValue value) {
        this.processId = processId;
        this.attrName = value.name();
        this.attrType = value.type().keyword();
        this.keywordValue = value.keyword();
        this.numberValue = value.number();
        this.dateValue = value.date();
    }

    public String getProcessId() {
        return processId;
    }

    public SearchAttributeValue toValue() {
        return new SearchAttributeValue(attrName, SearchAttributeType.of(attrType), keywordValue, numberValue, dateValue);
    }

    /** The primary key: one row per (process, attribute). */
    public static class Key implements Serializable {
        private String processId;
        private String attrName;

        public Key() {
        }

        public Key(String processId, String attrName) {
            this.processId = processId;
            this.attrName = attrName;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key key && Objects.equals(processId, key.processId) && Objects.equals(attrName, key.attrName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(processId, attrName);
        }
    }
}
