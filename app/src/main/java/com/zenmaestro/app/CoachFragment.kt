package com.zenmaestro.app

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zenmaestro.app.databinding.FragmentCoachBinding
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CoachFragment : Fragment() {

    private var _binding: FragmentCoachBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: PlanStore
    private lateinit var coachService: ZenCoachService
    private var requestRunning = false
    private var aiConsentGranted = false
    private var selectedAttachment: CoachAttachment? = null

    private val openAttachment = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSelectedAttachment(uri)
    }

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
        binding.attachButton.setOnClickListener {
            openAttachment.launch(SUPPORTED_PICKER_TYPES)
        }
        binding.removeAttachmentButton.setOnClickListener { clearAttachment() }
        binding.sendButton.setOnClickListener {
            sendMessage(binding.chatInput.text.toString(), selectedAttachment)
        }
        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage(binding.chatInput.text.toString(), selectedAttachment)
                true
            } else {
                false
            }
        }
        restoreLatestConversation()
    }

    private fun restoreLatestConversation() {
        viewLifecycleOwner.lifecycleScope.launch {
            val conversation = runCatching { coachService.loadLatestConversation() }.getOrNull()
            val currentBinding = _binding ?: return@launch
            if (conversation == null || conversation.messages.isEmpty()) return@launch
            currentBinding.chatList.removeAllViews()
            currentBinding.welcomeContainer.visibility = View.GONE
            conversation.messages.forEach { message ->
                addMessage(
                    message = message.content,
                    fromUser = message.role == "user",
                    attachmentName = message.attachmentName,
                )
            }
            scrollToLatest()
        }
    }

    private fun sendMessage(rawMessage: String, attachment: CoachAttachment? = null) {
        val message = rawMessage.trim().ifBlank {
            if (attachment != null) getString(R.string.attachment_default_prompt) else ""
        }
        if (message.isBlank() || requestRunning) return
        if (!aiConsentGranted) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.coach_ai_consent_title)
                .setMessage(R.string.coach_ai_consent_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_to_gemini) { _, _ ->
                    aiConsentGranted = true
                    requestAiReply(message, attachment)
                }
                .show()
            return
        }
        requestAiReply(message, attachment)
    }

    private fun requestAiReply(message: String, attachment: CoachAttachment?) {
        binding.welcomeContainer.visibility = View.GONE
        binding.coachOfflineBanner.visibility = View.GONE
        addMessage(message, fromUser = true, attachmentName = attachment?.displayName)
        binding.chatInput.text?.clear()
        val thinkingRow = addMessage(getString(R.string.coach_thinking), fromUser = false)
        setRequestRunning(true)
        scrollToLatest()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { coachService.reply(message, attachment) }
            val currentBinding = _binding ?: return@launch
            currentBinding.chatList.removeView(thinkingRow)
            currentBinding.coachOfflineBanner.visibility = if (result.isFailure) View.VISIBLE else View.GONE
            val reply = result.getOrElse {
                if (attachment == null) buildLocalContextReply()
                else getString(R.string.coach_attachment_failed)
            }
            addMessage(reply, fromUser = false)
            if (result.isSuccess && selectedAttachment === attachment) clearAttachment()
            setRequestRunning(false)
            scrollToLatest()
        }
    }

    private fun loadSelectedAttachment(uri: Uri) {
        if (requestRunning) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { readAttachment(uri) }
            }
            val attachment = result.getOrElse { error ->
                val message = when (error) {
                    is AttachmentTooLargeException -> R.string.attachment_too_large
                    is UnsupportedAttachmentException -> R.string.attachment_not_supported
                    else -> R.string.attachment_read_failed
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                return@launch
            }
            selectedAttachment = attachment
            binding.attachmentName.text = attachment.displayName
            binding.attachmentSize.text = getString(
                R.string.attachment_ready,
                formatFileSize(attachment.bytes.size)
            )
            binding.attachmentPreview.visibility = View.VISIBLE
        }
    }

    private fun readAttachment(uri: Uri): CoachAttachment {
        val resolver = requireContext().contentResolver
        var displayName = "attachment"
        var reportedSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                        displayName = cursor.getString(it) ?: displayName
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                        if (!cursor.isNull(it)) reportedSize = cursor.getLong(it)
                    }
                }
            }
        if ((reportedSize ?: 0L) > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()

        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        if (!isSupportedMimeType(mimeType)) throw UnsupportedAttachmentException()

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalStateException("Selected file cannot be opened")
        if (bytes.isEmpty()) throw IllegalStateException("Selected file is empty")
        return CoachAttachment(displayName.take(255), mimeType.lowercase(Locale.US), bytes)
    }

    private fun isSupportedMimeType(mimeType: String): Boolean {
        val normalized = mimeType.lowercase(Locale.US)
        return normalized in SUPPORTED_IMAGE_TYPES || normalized in SUPPORTED_DOCUMENT_TYPES
    }

    private fun clearAttachment() {
        selectedAttachment = null
        if (_binding == null) return
        binding.attachmentPreview.visibility = View.GONE
        binding.attachmentName.text = ""
        binding.attachmentSize.text = ""
    }

    private fun startNewConversation() {
        if (requestRunning) return
        coachService.resetConversation()
        clearAttachment()
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

    private fun addMessage(
        message: String,
        fromUser: Boolean,
        attachmentName: String? = null,
    ): View {
        val displayText = if (attachmentName == null) message else getString(
            R.string.attachment_message_format,
            message,
            attachmentName,
        )
        val bubble = TextView(requireContext()).apply {
            text = displayText
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
        binding.attachButton.isEnabled = !running
        binding.removeAttachmentButton.isEnabled = !running
        binding.realisticPromptButton.isEnabled = !running
        binding.nextPromptButton.isEnabled = !running
        binding.focusPromptButton.isEnabled = !running
        binding.sendButton.alpha = if (running) 0.55f else 1f
        binding.attachButton.alpha = if (running) 0.55f else 1f
    }

    private fun scrollToLatest() {
        binding.coachScroll.post { binding.coachScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatMinutes(minutes: Int): String = when {
        minutes < 60 -> getString(R.string.duration_minutes, minutes)
        minutes % 60 == 0 -> getString(R.string.duration_hours, minutes / 60)
        else -> String.format(Locale.US, "%dh %dm", minutes / 60, minutes % 60)
    }

    private fun formatFileSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private class AttachmentTooLargeException : RuntimeException()
    private class UnsupportedAttachmentException : RuntimeException()

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
        val SUPPORTED_PICKER_TYPES = arrayOf(
            "image/*",
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json",
        )
        val SUPPORTED_DOCUMENT_TYPES = setOf(
            "application/pdf",
            "application/json",
            "text/plain",
            "text/markdown",
            "text/csv",
        )
        val SUPPORTED_IMAGE_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/heic",
            "image/heif",
        )
    }
}
