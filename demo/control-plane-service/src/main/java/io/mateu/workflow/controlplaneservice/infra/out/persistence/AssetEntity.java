package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class AssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "asset_seq_gen")
    @SequenceGenerator(
            name = "asset_seq_gen",
            sequenceName = "asset_sequence",
            allocationSize = 1
    )
    Long id;

    String name;

    @Column(columnDefinition = "TEXT")
    String path;

    @Column(columnDefinition = "TEXT")
    String url;

    String countryCode;

}
