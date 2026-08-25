package io.mateu.workflow.application.out;

import java.util.List;

/**
 * One page of a step-execution listing, with the page the store actually served.
 *
 * @param content       the rows of the served page, most recently started first
 * @param totalElements how many step executions match the filter across every page
 * @param pageNumber    the zero-based page actually served
 * @param pageSize      the page size actually applied
 * @see ProcessSummaryPage for why the served page can differ from the requested one
 */
public record StepExecutionSummaryPage(List<StepExecutionSummary> content, long totalElements,
                                       int pageNumber, int pageSize) {
}
