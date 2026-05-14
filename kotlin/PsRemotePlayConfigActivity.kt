@file:Suppress(
    "KotlinUnusedImport",
    "RemoveRedundantQualifierName",
    "SpellCheckingInspection",
    "UnusedSymbol"
)

package com.platform.intentcontroller.ui.output

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.platform.intentcontroller.models.OutputConfig
import com.platform.intentcontroller.models.OutputType
import com.platform.intentcontroller.R
import com.platform.intentcontroller.storage.ProfileManager
import com.platform.intentcontroller.ui.output.OutputUiKit.BG_PRIMARY
import com.platform.intentcontroller.ui.output.OutputUiKit.BG_SECONDARY
import com.platform.intentcontroller.ui.output.OutputUiKit.DANGER
import com.platform.intentcontroller.ui.output.OutputUiKit.PRIMARY
import com.platform.intentcontroller.ui.output.OutputUiKit.SUCCESS
import com.platform.intentcontroller.ui.output.OutputUiKit.TEXT_PRIMARY
import com.platform.intentcontroller.ui.output.OutputUiKit.TEXT_SECONDARY
import com.platform.intentcontroller.ui.output.OutputUiKit.WARNING
import com.platform.intentcontroller.ui.output.OutputUiKit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * PsRemotePlayConfigActivity
 *
 * Config screen for the PS Remote Play output type.
 * Returns RESULT_OK with RESULT_OUTPUT_TYPE + settings extras.
 *
 * Settings keys written to profile.output.settings:
 *   "psHost"        — IP address string
 *   "psRegistKey"   — hex string of the 16-byte registration key
 *   "psMorning"     — hex string of the 16-byte morning secret
 *   "psAccountId"   — numeric PSN Account ID as a Long string
 *   "psIsPs5"       — "true" or "false"
 */
class PsRemotePlayConfigActivity : AppCompatActivity() {

    private lateinit var profileManager: ProfileManager

    private lateinit var etHost:          EditText
    private lateinit var etAccountId:     EditText
    private var isPs5:                    Boolean = true
    private lateinit var tvPairingStatus: TextView
    private lateinit var btnPair:         com.google.android.material.button.MaterialButton
    private lateinit var consoleLabelView: TextView
    private lateinit var hostLabelView:    TextView
    private lateinit var accountLabelView: TextView

    companion object {
        init {
            try { System.loadLibrary("chiaki") }
            catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "❌ libchiaki not loaded", e)
            }
        }
        private const val TAG = "PsRemotePlayConfig"

        const val EXTRA_OUTPUT_TYPE  = "OUTPUT_TYPE"
        const val RESULT_OUTPUT_TYPE = "SELECTED_OUTPUT_TYPE"

        const val SETTING_HOST       = "psHost"
        const val SETTING_REGIST_KEY = "psRegistKey"
        const val SETTING_MORNING    = "psMorning"
        const val SETTING_ACCOUNT_ID = "psAccountId"
        const val SETTING_IS_PS5     = "psIsPs5"

        private const val REGIST_KEY_BYTES = 16
        private const val MORNING_BYTES    = 16
        private const val PSN_ID_LOOKUP_URL = "https://psn.flipscreen.games/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileManager = ProfileManager(this)
        setContentView(buildUI())
        loadSavedSettings()
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUI(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_PRIMARY)
            setPadding(0, 0, 0, 32.dp(this@PsRemotePlayConfigActivity))
        }
        scroll.addView(root)

        root.addView(OutputUiKit.toolbar(this, getString(R.string.ps_remote_toolbar_title)) { finish() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Console Type ──────────────────────────────────────────────────────
        root.addView(OutputUiKit.sectionHeader(this, getString(R.string.ps_remote_console_type)))
        root.addView(OutputUiKit.buildToggleRow(
            ctx = this,
            options = listOf("PS5", "PS4"),
            selectedIndex = if (isPs5) 0 else 1,
            groupLabel = "console type"
        ) { idx -> isPs5 = (idx == 0) })

        // ── Connection ────────────────────────────────────────────────────────
        root.addView(OutputUiKit.sectionHeader(this, getString(R.string.ps_remote_connection)))

        hostLabelView = OutputUiKit.buildLabel(this, getString(R.string.ps_remote_host_label))
        root.addView(hostLabelView)
        etHost = OutputUiKit.buildEditText(
            this,
            hint = getString(R.string.ps_remote_host_hint),
            labelView = hostLabelView,
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
        )
        root.addView(etHost, OutputUiKit.paddedParams(this))

        root.addView(OutputUiKit.infoCard(this, getString(R.string.ps_remote_host_info)))

        // ── PSN Account ID ────────────────────────────────────────────────────
        root.addView(OutputUiKit.sectionHeader(this, getString(R.string.ps_remote_account_id_section)))

        accountLabelView = OutputUiKit.buildLabel(this, getString(R.string.ps_remote_account_id_label))
        root.addView(accountLabelView)
        etAccountId = OutputUiKit.buildEditText(
            this,
            hint = getString(R.string.ps_remote_account_id_hint),
            labelView = accountLabelView,
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        )
        root.addView(etAccountId, OutputUiKit.paddedParams(this))

        root.addView(buildAccountIdInfoCard())

        // ── Pairing ───────────────────────────────────────────────────────────
        root.addView(OutputUiKit.sectionHeader(this, getString(R.string.ps_remote_pairing_section)))
        root.addView(OutputUiKit.infoCard(this, getString(R.string.ps_remote_pairing_info)))

        // Live region announces pairing state changes to TalkBack (WCAG 4.1.3)
        tvPairingStatus = OutputUiKit.liveStatusText(this, getString(R.string.ps_remote_not_paired))
        root.addView(tvPairingStatus)

        btnPair = OutputUiKit.outlinedButton(this, getString(R.string.ps_remote_pair_btn),
            contentDesc = getString(R.string.ps_remote_pair_btn_content_desc)) { startPairing() }
        root.addView(btnPair, OutputUiKit.paddedParams(this))

        // ── Save ──────────────────────────────────────────────────────────────
        root.addView(OutputUiKit.sectionHeader(this, getString(R.string.ps_remote_save_section)))
        root.addView(OutputUiKit.primaryButton(this,
            text = getString(R.string.ps_remote_save_btn),
            contentDesc = getString(R.string.ps_remote_save_btn_content_desc)) {
            saveAndFinish()
        })

        return scroll
    }

    // ── Account ID info card with clickable link ──────────────────────────────

    private fun buildAccountIdInfoCard(): TextView {
        val prefix   = getString(R.string.ps_remote_account_id_info_prefix)
        val linkText = PSN_ID_LOOKUP_URL
        val suffix   = getString(R.string.ps_remote_account_id_info_suffix)
        val full     = prefix + linkText + suffix

        val spannable = SpannableString(full)
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(PSN_ID_LOOKUP_URL)))
                } catch (e: Exception) {
                    Toast.makeText(this@PsRemotePlayConfigActivity,
                        getString(R.string.ps_remote_browser_error), Toast.LENGTH_SHORT).show()
                }
            }
            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.color = PRIMARY
                ds.isUnderlineText = true
            }
        }, prefix.length, prefix.length + linkText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        return TextView(this).apply {
            text = spannable
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            setBackgroundColor(BG_SECONDARY)
            setPadding(24.dp(this@PsRemotePlayConfigActivity), 16.dp(this@PsRemotePlayConfigActivity),
                24.dp(this@PsRemotePlayConfigActivity), 16.dp(this@PsRemotePlayConfigActivity))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            // Hint to TalkBack that this contains an actionable link
            contentDescription = getString(R.string.ps_remote_account_id_content_desc, PSN_ID_LOOKUP_URL)
            layoutParams = OutputUiKit.paddedParams(this@PsRemotePlayConfigActivity)
        }
    }

    // ── Settings persistence ──────────────────────────────────────────────────

    private fun loadSavedSettings() {
        lifecycleScope.launch {
            val profile  = profileManager.loadProfile()
            val settings = profile.output?.settings ?: return@launch

            val savedHost = settings[SETTING_HOST] as? String ?: ""
            if (savedHost.isNotBlank()) etHost.setText(savedHost)

            val savedAccountId = settings[SETTING_ACCOUNT_ID] as? String ?: ""
            if (savedAccountId.isNotBlank()) etAccountId.setText(savedAccountId)

            isPs5 = (settings[SETTING_IS_PS5] as? String) != "false"

            val hasKeys = (settings[SETTING_REGIST_KEY] as? String)?.isNotBlank() == true
            if (hasKeys) setPairingStatus(paired = true)
        }
    }

    private fun setPairingStatus(
        paired: Boolean,
        inProgress: Boolean = false,
        message: String? = null
    ) {
        when {
            inProgress -> {
                tvPairingStatus.text = message ?: getString(R.string.ps_remote_pairing_waiting)
                tvPairingStatus.setTextColor(WARNING)
            }
            paired -> {
                tvPairingStatus.text = getString(R.string.ps_remote_pairing_success)
                tvPairingStatus.setTextColor(SUCCESS)
            }
            else -> {
                tvPairingStatus.text = message ?: getString(R.string.ps_remote_pairing_failed)
                tvPairingStatus.setTextColor(DANGER)
            }
        }
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty()) return false
        val parts = host.split(".")
        if (parts.size == 4) {
            return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } ?: false }
        }
        // Also accept hostnames (non-empty, no spaces, no special characters)
        return host.none { it == ' ' } && host.length <= 253
    }

    private fun startPairing() {
        val host      = etHost.text.toString().trim()
        val accountId = etAccountId.text.toString().trim().toLongOrNull()
        val isPs5Now  = isPs5

        if (!isValidHost(host)) {
            Toast.makeText(this, getString(R.string.ps_remote_enter_ip_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (accountId == null || accountId <= 0L) {
            Toast.makeText(this,
                getString(R.string.ps_remote_enter_account_id_toast, PSN_ID_LOOKUP_URL),
                Toast.LENGTH_LONG).show()
            return
        }

        setPairingStatus(paired = false, inProgress = true)
        btnPair.isEnabled = false

        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try { java.net.InetAddress.getByName(host).isReachable(2000) }
                catch (e: Exception) { false }
            }

            if (!reachable) {
                setPairingStatus(paired = false,
                    message = getString(R.string.ps_remote_reachable_error, host))
                btnPair.isEnabled = true
                return@launch
            }

            setPairingStatus(paired = false, inProgress = true,
                message = getString(R.string.ps_remote_pairing_enter_pin))

            val pin: Int? = suspendCancellableCoroutine { cont ->
                val input = OutputUiKit.buildEditText(
                    this@PsRemotePlayConfigActivity,
                    hint = getString(R.string.ps_remote_pin_hint),
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                ).apply { textSize = 20f }

                AlertDialog.Builder(this@PsRemotePlayConfigActivity)
                    .setTitle(getString(R.string.ps_remote_enter_pin_title))
                    .setMessage(getString(R.string.ps_remote_pin_dialog_msg, if (isPs5Now) "PS5" else "PS4"))
                    .setView(input)
                    .setPositiveButton(getString(R.string.ps_remote_pair_dialog_pair)) { _, _ ->
                        val parsed = input.text.toString().trim().toIntOrNull()
                        if (parsed != null) cont.resume(parsed)
                        else {
                            Toast.makeText(this@PsRemotePlayConfigActivity,
                                getString(R.string.ps_remote_pin_error), Toast.LENGTH_SHORT).show()
                            cont.resume(null)
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ -> cont.resume(null) }
                    .setOnCancelListener { cont.resume(null) }
                    .show()
            }

            if (pin == null) {
                setPairingStatus(paired = false, message = getString(R.string.ps_remote_pairing_cancelled))
                btnPair.isEnabled = true
                return@launch
            }

            setPairingStatus(paired = false, inProgress = true, message = getString(R.string.ps_remote_pairing_in_progress))

            val result = withContext(Dispatchers.IO) {
                try { nativeRegister(host, pin, accountId, isPs5Now) }
                catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "❌ nativeRegister: libchiaki not loaded", e); null
                }
                catch (e: Exception) {
                    Log.e(TAG, "❌ nativeRegister failed: ${e.message}", e); null
                }
            }

            if (result != null &&
                result.registKey.size == REGIST_KEY_BYTES &&
                result.morning.size   == MORNING_BYTES) {
                val profile  = profileManager.loadProfile()
                val settings = (profile.output?.settings ?: mutableMapOf()).toMutableMap()
                settings[SETTING_HOST]       = host
                settings[SETTING_REGIST_KEY] = result.registKey.toHexString()
                settings[SETTING_MORNING]    = result.morning.toHexString()
                settings[SETTING_ACCOUNT_ID] = accountId.toString()
                settings[SETTING_IS_PS5]     = isPs5Now.toString()
                profile.output = profile.output?.copy(settings = settings)
                    ?: OutputConfig(type = OutputType.PS_REMOTE_PLAY, settings = settings)
                profileManager.saveProfile(profile)
                setPairingStatus(paired = true)
                Log.i(TAG, "✅ Paired with ${if (isPs5Now) "PS5" else "PS4"} at $host")
            } else {
                setPairingStatus(paired = false)
                Log.e(TAG, "❌ Pairing failed — result=$result")
            }

            btnPair.isEnabled = true
        }
    }

    // ── Save & finish ─────────────────────────────────────────────────────────

    private fun saveAndFinish() {
        val host = etHost.text.toString().trim()
        if (!isValidHost(host)) {
            Toast.makeText(this, getString(R.string.ps_remote_enter_ip), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val profile  = profileManager.loadProfile()
            val settings = (profile.output?.settings ?: mutableMapOf()).toMutableMap()

            val registKey = settings[SETTING_REGIST_KEY] as? String ?: ""
            val morning   = settings[SETTING_MORNING]    as? String ?: ""

            if (registKey.isBlank() || morning.isBlank()) {
                Toast.makeText(this@PsRemotePlayConfigActivity,
                    getString(R.string.ps_remote_pair_first_toast), Toast.LENGTH_LONG).show()
                return@launch
            }

            val accountId = settings[SETTING_ACCOUNT_ID] as? String ?: ""
            val isPs5Now  = (settings[SETTING_IS_PS5] as? String) != "false"
            settings[SETTING_HOST] = host

            setResult(RESULT_OK, Intent().apply {
                putExtra(RESULT_OUTPUT_TYPE, OutputType.PS_REMOTE_PLAY.name)
                putExtra(SETTING_HOST,       host)
                putExtra(SETTING_REGIST_KEY, registKey)
                putExtra(SETTING_MORNING,    morning)
                putExtra(SETTING_ACCOUNT_ID, accountId)
                putExtra(SETTING_IS_PS5,     isPs5Now.toString())
            })
            finish()
        }
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    private external fun nativeRegister(
        host:      String,
        pin:       Int,
        accountId: Long,
        isPs5:     Boolean
    ): RegisterResult?

    data class RegisterResult(val registKey: ByteArray, val morning: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RegisterResult
            return registKey.contentEquals(other.registKey) && morning.contentEquals(other.morning)
        }
        override fun hashCode() = 31 * registKey.contentHashCode() + morning.contentHashCode()
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}
