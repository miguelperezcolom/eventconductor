package io.mateu.workflow.controlplaneservice.infra.out.scrapper;

import lombok.With;

@With
public record DownloadResult(byte[] content, int statusCode, long milliSeconds) {
}
