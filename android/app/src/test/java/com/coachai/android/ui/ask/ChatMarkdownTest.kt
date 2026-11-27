package com.coachai.android.ui.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun headingsBoldAndListsRenderWithoutRawMarkers() {
        val rendered = renderChatMarkdown("### **Facts**\n* **Plan:** Focus now")

        assertEquals("Facts\n• Plan: Focus now", rendered.text)
        assertFalse(rendered.text.contains("**"))
        assertFalse(rendered.text.contains("###"))
        assertTrue(rendered.spanStyles.isNotEmpty())
    }

    @Test
    fun arabicTextIsDetectedForRtlAlignment() {
        assertTrue(isRtlChatText("ساعدني أنظم يومي"))
        assertFalse(isRtlChatText("Help me plan today"))
    }
}
