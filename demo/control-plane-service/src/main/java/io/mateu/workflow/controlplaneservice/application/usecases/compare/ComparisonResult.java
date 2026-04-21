package io.mateu.workflow.controlplaneservice.application.usecases.compare;

public record ComparisonResult(String page, String maskedUrl, String transparentMaskedUrl, String diffUrl, double similarity) {
}
