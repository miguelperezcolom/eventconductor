package io.mateu.workflow.controlplaneservice.infra.out.persistence.web;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class RouteEntity {

    @Id
    String id;

    String path;

    String languageId;

    String pageId;

    String assetIdsJson;

}
