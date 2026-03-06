package io.mateu.workflow.infra.out.persistence.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Version;

@Entity
@Getter@Setter
@AllArgsConstructor@NoArgsConstructor
@Table(name = "generic_entity", indexes = {
        @Index(name = "idx_generic_entity_type", columnList = "type")
})
public class GenericEntity {

    @Id
    private String id;
    String name;
    String type;
    @Version
    int version;
    @Column(name = "json", columnDefinition = "TEXT")
    String json;

}
