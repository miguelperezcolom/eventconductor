package io.mateu.workflow.application.out;

import java.util.List;

/**
 * One page of a process listing: the rows, the total the filter matched, and — because the store is
 * what decides them — the page number and size it actually served.
 *
 * <p>Those last two are not always the ones asked for. A request past the last page is answered
 * with the last real page, so a stale deep link recovers instead of showing an empty grid, and a
 * request carrying no size is answered with everything on one page. The pager divides by both, so
 * neither may come back as zero.
 *
 * @param content       the rows of the served page, newest first
 * @param totalElements how many processes match the filter across every page
 * @param pageNumber    the zero-based page actually served
 * @param pageSize      the page size actually applied
 */
public record ProcessSummaryPage(List<ProcessSummary> content, long totalElements,
                                 int pageNumber, int pageSize) {
}
