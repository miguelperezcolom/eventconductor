package io.mateu.workflow.webhook;

import java.util.List;

/**
 * The interesting bits of an inbound git push webhook: which repository was pushed and to
 * which branch. Either field may be empty/null when the payload could not be understood, in
 * which case callers fall back to reimporting everything (unchanged legacy behaviour).
 */
public record GitPushPayload(List<String> repositoryUrls, String branch) {

    public static final GitPushPayload EMPTY = new GitPushPayload(List.of(), null);

    public boolean isEmpty() {
        return (repositoryUrls == null || repositoryUrls.isEmpty())
                && (branch == null || branch.isBlank());
    }
}
