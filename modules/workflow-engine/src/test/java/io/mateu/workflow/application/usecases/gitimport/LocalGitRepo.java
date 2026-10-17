package io.mateu.workflow.application.usecases.gitimport;

import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A git "remote" for import tests: a bare repository in a temp directory, reached over a
 * {@code file://} URL, plus a working clone that commits and pushes to it. No network.
 */
public final class LocalGitRepo implements AutoCloseable {

    private final Path bare;
    private final Git work;

    public LocalGitRepo(Path root) throws Exception {
        this(root, "main");
    }

    public LocalGitRepo(Path root, String branch) throws Exception {
        this.bare = root.resolve("origin.git");
        Git.init().setBare(true).setInitialBranch(branch).setDirectory(bare.toFile()).call().close();
        this.work = Git.init().setInitialBranch(branch).setDirectory(root.resolve("work").toFile()).call();
        work.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(url())).call();
    }

    /** The URL the import clones from. */
    public String url() {
        return bare.toUri().toString();
    }

    /** Writes the given files (path to content; null content deletes), commits and pushes. */
    public LocalGitRepo push(Map<String, String> files) throws Exception {
        var root = work.getRepository().getWorkTree().toPath();
        for (var entry : files.entrySet()) {
            var file = root.resolve(entry.getKey());
            if (entry.getValue() == null) {
                Files.deleteIfExists(file);
                work.rm().addFilepattern(entry.getKey()).call();
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
                work.add().addFilepattern(entry.getKey()).call();
            }
        }
        work.commit().setMessage("change").setSign(false)
                .setAuthor("test", "test@example.com").setCommitter("test", "test@example.com").call();
        work.push().setRemote("origin").call();
        return this;
    }

    @Override
    public void close() throws IOException {
        work.close();
    }
}
