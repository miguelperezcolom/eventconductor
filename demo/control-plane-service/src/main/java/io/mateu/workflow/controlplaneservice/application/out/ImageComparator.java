package io.mateu.workflow.controlplaneservice.application.out;

import java.nio.file.Path;

public interface ImageComparator {

    ImageComparisonResult compare(String key, String url1, String url2);

}
