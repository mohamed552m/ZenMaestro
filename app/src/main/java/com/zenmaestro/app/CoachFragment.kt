package com.zenmaestro.app

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zenmaestro.app.databinding.FragmentCoachBinding
import java.util.Locale
import kotlinx.coroutines.launch

class CoachFragment : Fragment() {

    private var _binding: FragmentCoachBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: PlanStore
    private lateinit var coachService: ZenCoachService
    private var requestRunning = false
    private var aiConsentGranted = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCoachBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = PlanStore(requireContext())
        coachService = ZenCoachService()
        binding.realisticPromptButton.setOnClickListener {
            sendMessage(getString(R.string.prompt_realistic))
        }
        binding.nextPromptButton.setOnClickListener {
            sendMessage(getString(R.string.prompt_next))
        }
        binding.focusPromptButton.setOnClickListener {
            sendMessage(getString(R.string.prompt_focus))
        }
        binding.coachMenuButton.setOnClickListener { startNewConversation() }
        binding.sendButton.setOnClickListener { sendMessage(binding.chatInput.text.toString()) }
        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage(binding.chatInput.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun sendMessage(rawMessage: String) {
        val message = rawMessage.trim()
        if (message.isBlank() || requestRunning) return
        if (!aiConsentGranted) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.coach_ai_consent_title)
                .setMessage(R.string.coach_ai_consent_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_to_gemini) { _, _ ->
                    aiConsentGranted = true
                    requestAiReply(message)
                }
                .show()
            return
        }
        requestAiReply(message)
    }

    private fun requestAiReply(message: String) {
        binding.welcomeContainer.visibility = View.GONE
        binding.coachOfflineBanner.visibility = View.GONE
        addMessage(message, fromUser = true)
        binding.chatInput.text?.clear()
        val thinkingRow = addMessage(getString(R.string.coach_thinking), fromUser = false)
        setRequestRunning(true)
        scrollToLatest()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { coachService.reply(message) }
            val currentBinding = _binding ?: return@launch
            currentBinding.chatList.removeView(thinkingRow)
            currentBinding.coachOfflineBanner.visibility = if (result.isFailure) View.VISIBLE else View.GONE
            val reply = result.getOrElse { buildLocalContextReply() }
            addMessage(reply, fromUser = false)
            setRequestRunning(false)
            scrollToLatest()
        }
    }

    private fun startNewConversation() {
        if (requestRunning) return
        coachService.resetConversation()
        binding.chatList.removeAllViews()
        binding.chatInput.text?.clear()
        binding.coachOfflineBanner.visibility = View.GONE
        binding.welcomeContainer.visibility = View.VISIBLE
        binding.coachScroll.post { binding.coachScroll.fullScroll(View.FOCUS_UP) }
    }

    private fun buildLocalContextReply(): String {
        val tasks = store.load().filterNot { it.completed || it.isBreak }
        if (tasks.isEmpty()) return getString(R.string.coach_no_plan_reply)
        val minutes = tasks.sumOf { it.durationMinutes }
        return getString(
            R.string.coach_plan_reply,
            tasks.size,
            formatMinutes(minutes),
            tasks.minByOrNull { it.priority }?.title.orEmpty()
        )
    }

    private fun addMessage(message: String, fromUser: Boolean): View {
        val bubble = TextView(requireContext()).apply {
            text = message
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (fromUser) R.color.chalk_white else R.color.chalkboard
                )
            )
            textSize = 14f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (fromUser) R.color.chalkboard else R.color.sage_tint
                    )
                )
            }
        }
        val row = android.widget.FrameLayout(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(
                bubble,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (fromUser) Gravity.END else Gravity.START
                    topMargin = dp(10)
                }
            )
        }
        binding.chatList.addView(row)
        return row
    }

    private fun setRequestRunning(running: Boolean) {
        requestRunning = running
        binding.chatInput.isEnabled = !running
        binding.sendButton.isEnabled = !running
        binding.realisticPromptButton.isEnabled = !running
        binding.nextPromptButton.isEnabled = !running
        binding.focusPromptButton.isEnabled = !running
        binding.sendButton.alpha = if (running) 0.55f else 1f
    }

    private fun scrollToLatest() {
        binding.coachScroll.post { binding.coachScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatMinutes(minutes: Int): String = when {
        minutes < 60 -> getString(R.string.duration_minutes, minutes)
        minutes % 60 == 0 -> getString(R.string.duration_hours, minutes / 60)
        else -> String.format(Locale.US, "%dh %dm", minutes / 60, minutes % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
