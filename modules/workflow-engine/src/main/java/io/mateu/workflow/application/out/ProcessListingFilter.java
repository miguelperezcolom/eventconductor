package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.ProcessStatus;

import java.time.LocalDateTime;

/**
 * Everything the process listing filters by, in one value.
 *
 * <p>A record rather than a fifth, sixth and seventh parameter on {@code searchSummaries}. Every
 * store has to apply all of it — one that quietly ignored a field would serve a page that does not
 * answer the question that was asked, and the operator has no way to tell — and a record is what
 * makes "all of it" something the compiler notices when the set grows again.
 *
 * <p>Every field is optional; null means "do not narrow by this". {@code onlyErrors} is the
 * exception, a boolean because it predates the rest and is a toggle rather than a value. It is
 * redundant with {@code status = ERROR} and both are honoured, ANDed like everything else.
 *
 * @param searchText           matched against name and business key, case-insensitively
 * @param onlyErrors           the pre-existing toggle
 * @param workflowDefinitionId exact match
 * @param status               exact match
 * @param createdFrom          inclusive lower bound on creation
 * @param createdTo            inclusive upper bound on creation
 */
public record ProcessListingFilter(
        String searchText,
        boolean onlyErrors,
        String workflowDefinitionId,
        ProcessStatus status,
        LocalDateTime createdFrom,
        LocalDateTime createdTo) {

    /** The listing with nothing but a text box and the errors toggle — what every caller had. */
    public static ProcessListingFilter of(String searchText, boolean onlyErrors) {
        return new ProcessListingFilter(searchText, onlyErrors, null, null, null, null);
    }

    /**
     * Whether anything beyond text and the errors toggle is set.
     *
     * <p>Asked by the read-model path, which can apply those two and none of the others. It answers
     * by declining the listing rather than by serving one that ignores half the filter — see
     * {@code SimpleProcessCrudAdapter}.
     */
    public boolean hasNarrowingBeyondText() {
        return workflowDefinitionId != null || status != null
                || createdFrom != null || createdTo != null;
    }

    /** Null and blank both mean "no text filter"; normalised here so no store has to decide. */
    public String normalisedSearchText() {
        return searchText == null || searchText.isBlank() ? null : searchText;
    }
}
