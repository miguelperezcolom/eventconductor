package com.eventconductor.intellij

import com.intellij.openapi.editor.Document

/**
 * The one span where two texts differ: `[start, end)` in [old] and the text that replaces it.
 * The common prefix and suffix are left out, so a visual edit that changed one line touches one
 * line of the document rather than replacing all of it.
 */
data class ChangedSpan(val start: Int, val end: Int, val replacement: String)

fun changedSpan(old: String, new: String): ChangedSpan {
    var start = 0
    val max = minOf(old.length, new.length)
    while (start < max && old[start] == new[start]) start++
    var oldEnd = old.length
    var newEnd = new.length
    while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return ChangedSpan(start, oldEnd, new.substring(start, newEnd))
}

/** Makes [doc] read [newText] by replacing only the span that differs (call inside a write action). */
fun replaceChangedSpan(doc: Document, newText: String) {
    val span = changedSpan(doc.text, newText)
    if (span.start == span.end && span.replacement.isEmpty()) return
    doc.replaceString(span.start, span.end, span.replacement)
}
