package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class RouteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "route_seq_gen")
    @SequenceGenerator(
            name = "route_seq_gen",
            sequenceName = "route_sequence",
            allocationSize = 1
    )
    Long id;

    String name;

    String languageCode;

    String countryCode;

    Long pageId;

    @Column(columnDefinition = "TEXT")
    String path;

    @Column(columnDefinition = "TEXT")
    String url;

    @Column(columnDefinition = "TEXT")
    String hash;

    @Column(columnDefinition = "TEXT")
    String deployedHash;

    Long releaseId;

}
