package com.coachai.android.ui.ask

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

internal fun renderChatMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    source.lines().forEachIndexed { index, rawLine ->
        if (index > 0) append('\n')
        if (rawLine.trim() == "---") return@forEachIndexed

        val (headingLevel, withoutHeading) = stripHeading(rawLine)
        val line = normalizeListMarker(withoutHeading)
        val paragraphStart = length
        appendInlineMarkdown(line, headingLevel)
        if (containsArabic(line)) {
            addStyle(
                ParagraphStyle(textDirection = TextDirection.Rtl),
                paragraphStart,
                length,
            )
        }
    }
}

internal fun isRtlChatText(source: String): Boolean = source.any(::isArabicCharacter)

private fun AnnotatedString.Builder.appendInlineMarkdown(line: String, headingLevel: Int?) {
    var cursor = 0
    while (cursor < line.length) {
        val boldStart = line.indexOf("**", cursor)
        if (boldStart < 0) {
            appendStyled(line.substring(cursor), headingLevel, bold = false)
            break
        }
        appendStyled(line.substring(cursor, boldStart), headingLevel, bold = false)
        val boldEnd = line.indexOf("**", boldStart + 2)
        if (boldEnd < 0) {
            appendStyled(line.substring(boldStart), headingLevel, bold = false)
            break
        }
        appendStyled(line.substring(boldStart + 2, boldEnd), headingLevel, bold = true)
        cursor = boldEnd + 2
    }
}

private fun AnnotatedString.Builder.appendStyled(
    value: String,
    headingLevel: Int?,
    bold: Boolean,
) {
    if (value.isEmpty()) return
    val style = SpanStyle(
        fontWeight = if (bold || headingLevel != null) FontWeight.SemiBold else null,
        fontSize = when (headingLevel) {
            1 -> 20.sp
            2 -> 18.sp
            3 -> 16.sp
            else -> 14.sp
        },
    )
    pushStyle(style)
    append(value)
    pop()
}

private fun stripHeading(line: String): Pair<Int?, String> {
    val trimmed = line.trimStart()
    val markerLength = trimmed.takeWhile { it == '#' }.length
    return if (markerLength in 1..3 && trimmed.getOrNull(markerLength) == ' ') {
        markerLength to trimmed.drop(markerLength + 1)
    } else {
        null to line
    }
}

private fun normalizeListMarker(line: String): String {
    val leading = line.takeWhile(Char::isWhitespace)
    val content = line.drop(leading.length)
    return when {
        content.startsWith("- ") -> "$leading• ${content.drop(2)}"
        content.startsWith("* ") -> "$leading• ${content.drop(2)}"
        else -> line
    }
}

private fun containsArabic(value: String): Boolean = value.any(::isArabicCharacter)

private fun isArabicCharacter(character: Char): Boolean =
    character in '\u0600'..'\u06FF' ||
        character in '\u0750'..'\u077F' ||
        character in '\u08A0'..'\u08FF'
