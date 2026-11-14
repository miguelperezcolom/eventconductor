package com.eventconductor.intellij

import junit.framework.TestCase

/** A visual edit lands in the document as the one span that changed, not as a whole-file replace. */
class DocumentEditsTest : TestCase() {

    fun testOnlyTheDifferingSpanIsReplaced() {
        val old = "# keep me\nname: Order\nsteps: []\n"
        val new = "# keep me\nname: Orders\nsteps: []\n"
        val span = changedSpan(old, new)
        assertEquals(ChangedSpan(21, 21, "s"), span)
        assertEquals(new, old.substring(0, span.start) + span.replacement + old.substring(span.end))
    }

    fun testAppendAndDeleteAndNoChange() {
        assertEquals(ChangedSpan(3, 3, "d\n"), changedSpan("ab\n", "ab\nd\n"))
        assertEquals(ChangedSpan(2, 4, ""), changedSpan("a\nb\n", "a\n"))
        assertEquals(ChangedSpan(4, 4, ""), changedSpan("same", "same"))
    }
}
