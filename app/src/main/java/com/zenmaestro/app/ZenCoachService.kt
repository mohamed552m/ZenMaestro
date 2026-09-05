package com.zenmaestro.app

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content

class ZenCoachService {

    private val model = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel(
            modelName = MODEL_NAME,
            systemInstruction = content { text(SYSTEM_INSTRUCTION) }
        )
    private var chat = model.startChat()

    suspend fun reply(message: String): String {
        val response = chat.sendMessage(message)
        val reply = response.text?.trim().takeUnless { it.isNullOrBlank() }
            ?: throw IllegalStateException("Empty AI response")
        trimHistory()
        return reply
    }

    fun resetConversation() {
        chat = model.startChat()
    }

    private fun trimHistory() {
        if (chat.history.size <= MAX_HISTORY_ITEMS) return
        chat = model.startChat(chat.history.takeLast(MAX_HISTORY_ITEMS))
    }

    private companion object {
        const val MODEL_NAME = "gemini-3.5-flash-lite"
        const val MAX_HISTORY_ITEMS = 20
        const val SYSTEM_INSTRUCTION = """
            You are Zen, a calm and practical AI learning coach inside ZenMaestro.
            Reply in the same language as the learner.
            Remember facts the learner shares during this conversation and use them when relevant.
            Do not claim you can see their tasks or progress because no plan data is being shared.
            Never change their plan. Offer concise, practical guidance under 140 words.
        """
    }
}
