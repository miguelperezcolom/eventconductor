package io.mateu.workflow.contentservice.application.query.dto;

import io.mateu.workflow.contentservice.application.usecases.content.ContentValueDto;

import java.util.List;

public record ContentDto(String id,
                         String name,
                         String contentType,
                         List<String> labels,
                         List<ContentValueDto> values) {
}
