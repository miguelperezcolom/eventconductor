package io.mateu.workflow.uie2e;

import com.microsoft.playwright.Page;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI-FILTER-CHIP-CLEAR — clicking a filter chip's ✕ removes the chip (not just the filtering).
 *
 * <p>Regression (Mateu): the ✕ cleared the filter from the results but the chip stayed on the bar,
 * because the component's "keep edited field" defence restored the pre-clear value over the clear.
 */
class FilterChipClearJourneyTest extends AbstractUiE2eTest {

    private int chipCount() {
        Object r = page.evaluate("() => {let n=0;const seen=new Set();const walk=(root)=>{root.querySelectorAll('*').forEach(el=>{"
                + "const al=(el.getAttribute&&(el.getAttribute('aria-label')||''))||'';"
                + "if(/^Remove filter/i.test(al)&&el.offsetParent!==null)n++;"
                + "if(el.shadowRoot&&!seen.has(el.shadowRoot)){seen.add(el.shadowRoot);walk(el.shadowRoot);}});};walk(document);return n;}");
        return ((Number) r).intValue();
    }

    private String itemCount() {
        return page.getByText(java.util.regex.Pattern.compile("\\d+ items?")).first().innerText();
    }

    @Test
    void removing_a_filter_chip_removes_it_and_the_filtering() {
        startProcess("ui-greeting", "chip-alpha", new Variable("who", "a"));
        startProcess("ui-greeting", "chip-beta", new Variable("who", "b"));
        ui.goToProcesses();
        page.waitForTimeout(1000);
        assertThat(itemCount()).isEqualTo("2 items");

        // Apply the free-text filter → one chip, one row.
        var s = page.getByPlaceholder("Search").first();
        s.fill("chip-alpha"); s.press("Enter"); page.waitForTimeout(1200);
        assertThat(chipCount()).as("a chip appears").isEqualTo(1);
        assertThat(itemCount()).isEqualTo("1 item");

        // Click the chip's ✕: the filter clears AND the chip disappears (and stays gone).
        page.getByLabel(java.util.regex.Pattern.compile("^Remove filter")).first().click();
        page.waitForTimeout(2500);
        assertThat(itemCount()).as("the filtering is cleared").isEqualTo("2 items");
        assertThat(chipCount()).as("the chip is gone after clicking its ✕").isEqualTo(0);
    }
}
