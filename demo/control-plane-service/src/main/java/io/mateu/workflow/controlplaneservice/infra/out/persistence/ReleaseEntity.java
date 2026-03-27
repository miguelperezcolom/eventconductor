package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseDate;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.UserId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
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
public class ReleaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "release_seq_gen")
    @SequenceGenerator(
            name = "release_seq_gen",
            sequenceName = "release_sequence",
            allocationSize = 1
    )
    Long id;

    String name;

    String userId;

    LocalDateTime date;

    @Column(columnDefinition = "TEXT")
    String languageCodesJson;

    @Column(columnDefinition = "TEXT")
    String pageIdsJson;

    @Column(columnDefinition = "TEXT")
    String countryCodesJson;

    String environmentId;

    String siteId;

}
