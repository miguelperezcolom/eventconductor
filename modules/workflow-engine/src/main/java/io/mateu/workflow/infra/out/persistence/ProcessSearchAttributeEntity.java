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
 * One search attribute of a process (V48): its value in the one column its type uses — keyword_value
 * for keyword, text and bool; number_value; date_value. Written at the process save chokepoint when the
 * values change; read by the listing through {@code EXISTS} conditions, answered by the
 * (name, value) indexes. Deleted with the process by the retention purge.
 */
@Entity
@Table(name = "process_search_attribute", indexes = {
        @Index(name = "idx_psa_keyword", columnList = "attr_name, keyword_value"),
        @Index(name = "idx_psa_number", columnList = "attr_name, number_value"),
        @Index(name = "idx_psa_date", columnList = "attr_name, date_value")
})
@IdClass(ProcessSearchAttributeEntity.Key.class)
public class ProcessSearchAttributeEntity {

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

    protected ProcessSearchAttributeEntity() {
    }

    public ProcessSearchAttributeEntity(String processId, SearchAttributeValue value) {
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
