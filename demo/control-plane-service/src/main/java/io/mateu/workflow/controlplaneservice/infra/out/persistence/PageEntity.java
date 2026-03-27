package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

}
