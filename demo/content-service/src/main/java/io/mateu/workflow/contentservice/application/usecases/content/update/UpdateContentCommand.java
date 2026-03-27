package io.mateu.workflow.contentservice.application.usecases.content.update;

import io.mateu.workflow.contentservice.application.usecases.content.ContentValueDto;

import java.util.List;

public record UpdateContentCommand(String id, String name, String contentType, List<String> labels, List<ContentValueDto> values) {
}
