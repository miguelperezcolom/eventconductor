package io.mateu.workflow.contentservice.application.usecases.content.create;

import io.mateu.workflow.contentservice.application.usecases.content.ContentValueDto;

import java.util.List;

public record CreateContentCommand(String name, String contentType, List<String> labels, List<ContentValueDto> values) {
}
