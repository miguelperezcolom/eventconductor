package io.mateu.workflow.codegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips a generated project into the bytes the UI hands a user. Pure and deterministic — entries in a
 * stable order, no timestamps — so the same project always zips to the same archive. Extra files
 * (the {@code .ectask} contracts, so the download builds on its own) are merged in by path.
 */
public final class ProjectZipper {

    private ProjectZipper() {
    }

    public static byte[] zip(GeneratedProject project, Map<String, String> extraFiles) {
        var files = new LinkedHashMap<String, String>();
        for (var file : project.files()) {
            files.put(file.path(), file.content());
        }
        if (extraFiles != null) {
            files.putAll(extraFiles);
        }

        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : files.entrySet()) {
                var e = new ZipEntry(entry.getKey());
                e.setTime(0L); // deterministic: no wall-clock in the archive
                zip.putNextEntry(e);
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
