package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class SiteEntity {

    @Id
    String id;

    String name;

    @Column(columnDefinition = "TEXT")
    String url;

    @Column(columnDefinition = "TEXT")
    String llmsTxt;

}
