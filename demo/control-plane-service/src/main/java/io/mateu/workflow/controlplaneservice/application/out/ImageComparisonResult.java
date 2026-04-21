package io.mateu.workflow.controlplaneservice.application.out;

public record ImageComparisonResult(String maskedUrl, String transparentMaskedUrl, String diff, double similarity) {
}
