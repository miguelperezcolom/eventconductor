package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageChangeFrequency;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageCheck;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageLastModification;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PagePriority;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class PageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "page_seq_gen")
    @SequenceGenerator(
            name = "page_seq_gen",
            sequenceName = "page_sequence",
            allocationSize = 1
    )
    Long id;

    String siteId;

    String name;

    @Column(columnDefinition = "TEXT")
    String path;

    @Column(columnDefinition = "TEXT")
    String jsonLd;

    boolean dependsOnLanguage;

    boolean dependsOnCountry;

    String changeFrequency;

    double priority;

    LocalDateTime lastModification;

    String checks;

}
