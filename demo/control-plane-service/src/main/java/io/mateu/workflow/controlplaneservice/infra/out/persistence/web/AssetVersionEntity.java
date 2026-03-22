package io.mateu.workflow.controlplaneservice.infra.out.persistence.web;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class AssetVersionEntity {

    @Id
    String id;

    String assetId;

    String resourceId;

    String releaseId;

}
