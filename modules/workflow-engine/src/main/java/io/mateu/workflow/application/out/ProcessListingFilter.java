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
 * <p>{@code businessKey} narrows by that field alone, unlike {@code searchText} which matches it or
 * the name. It is the business identifier an operator has in hand — an order number, a booking
 * reference — and filtering a listing down to it is the query they reach for most.
 *
 * @param searchText           matched against name and business key, case-insensitively
 * @param onlyErrors           the pre-existing toggle
 * @param workflowDefinitionId exact match
 * @param status               exact match
 * @param createdFrom          inclusive lower bound on creation
 * @param createdTo            inclusive upper bound on creation
 * @param businessKey          matched against business key alone, case-insensitively, as a substring
 */
public record ProcessListingFilter(
        String searchText,
        boolean onlyErrors,
        String workflowDefinitionId,
        ProcessStatus status,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        String businessKey) {

    /** The listing with nothing but a text box and the errors toggle — what every caller had. */
    public static ProcessListingFilter of(String searchText, boolean onlyErrors) {
        return new ProcessListingFilter(searchText, onlyErrors, null, null, null, null, null);
    }

    /**
     * The shape every caller had before a business-key filter existed. Kept so those callers — and
     * the tests that pin the other filters — read unchanged; a null business key narrows nothing.
     */
    public ProcessListingFilter(String searchText, boolean onlyErrors, String workflowDefinitionId,
                                ProcessStatus status, LocalDateTime createdFrom, LocalDateTime createdTo) {
        this(searchText, onlyErrors, workflowDefinitionId, status, createdFrom, createdTo, null);
    }

    /**
     * Whether anything beyond text and the errors toggle is set.
     *
     * <p>Asked by the read-model path, which can apply those two and none of the others. It answers
     * by declining the listing rather than by serving one that ignores half the filter — see
     * {@code SimpleProcessCrudAdapter}. The business key is on this side of the line with the other
     * value filters: the index could match it, but the listing keeps one rule — text and errors on
     * the index, everything else on the write side — rather than a per-field patchwork.
     */
    public boolean hasNarrowingBeyondText() {
        return workflowDefinitionId != null || status != null
                || createdFrom != null || createdTo != null || normalisedBusinessKey() != null;
    }

    /** Null and blank both mean "no text filter"; normalised here so no store has to decide. */
    public String normalisedSearchText() {
        return searchText == null || searchText.isBlank() ? null : searchText;
    }

    /** Null and blank both mean "no business-key filter"; normalised here so no store has to decide. */
    public String normalisedBusinessKey() {
        return businessKey == null || businessKey.isBlank() ? null : businessKey;
    }
}
