package io.mateu.workflow.paging;

/**
 * The page a listing actually gets, given what it asked for and how many rows matched.
 *
 * <p>Not simply what was asked for. A size of zero or less means "everything on one page", and a
 * page past the end is answered with the last real one, so a stale deep link recovers rather than
 * showing an empty grid. Neither number may come back as zero: the pager on the other end divides
 * by the size, and a zero there read as "Page 3423 of Infinity", with next and last enabled for
 * ever.
 *
 * <p>Lives in the shared module because it is the one piece of paging that has to mean the same
 * thing everywhere it is answered: in memory, in SQL against the write side, and in SQL against the
 * read model — three stores in two modules, and a listing whose page numbering depended on which
 * one answered would be a listing that renumbers itself when you turn the read model on.
 *
 * @param number the zero-based page to serve
 * @param size   the page size to apply
 */
public record ServedPage(int number, int size) {

    /**
     * @param requestedPage zero-based page the caller asked for
     * @param requestedSize rows per page the caller asked for; zero or less means all of them
     * @param total         how many rows matched the filter
     */
    public static ServedPage of(int requestedPage, int requestedSize, long total) {
        var size = requestedSize > 0
                ? requestedSize
                : (int) Math.max(1, Math.min(total, Integer.MAX_VALUE));
        var lastPage = total == 0 ? 0 : (int) ((total - 1) / size);
        return new ServedPage(Math.min(Math.max(requestedPage, 0), lastPage), size);
    }

    /** How many rows to skip to reach this page. */
    public long offset() {
        return (long) number * size;
    }
}
