package ai.firewhale

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import ai.firewhale.BuildConfig.FACTCHECK_API_KEY
import ai.firewhale.BuildConfig.GEMINI_API_KEY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class ScreenScanAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ScreenScanService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var scanButton: TextView? = null
    private var warningBanner: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 120
        }
        addOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    private fun addOverlay() {
        if (scanButton != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val circle = TextView(this).apply {
            text = "Scan"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(24, 24, 24, 24)
            isClickable = true
            isFocusable = true
            setOnClickListener { triggerScan() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1E88E5.toInt())
                setStroke(3, 0xFFFFFFFF.toInt())
            }
        }

        val params = WindowManager.LayoutParams(
            210,
            210,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 40
            y = 0
        }

        windowManager?.addView(circle, params)
        scanButton = circle
    }

    private fun removeOverlay() {
        warningBanner?.let { windowManager?.removeView(it) }
        scanButton?.let { windowManager?.removeView(it) }
        warningBanner = null
        scanButton = null
    }

    private fun triggerScan() {
        if (GEMINI_API_KEY.isBlank()) {
            showToast("Gemini API key missing. Set GEMINI_API_KEY in local.properties.")
            return
        }

        val scanText = try {
            val root = rootInActiveWindow ?: throw IllegalStateException("No active window")
            collectScreenContent(root)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to collect screen content", t)
            showToast("Unable to read visible content on this screen.")
            return
        }

        if (scanText.isBlank()) {
            showToast("No active content to scan.")
            return
        }

        serviceScope.launch {
            try {
                val screenshotBase64 = captureScreenshotBase64()
                val verdict = GeminiVerifier.verify(scanText, screenshotBase64, GEMINI_API_KEY)
                mainHandler.post {
                    when (verdict.riskLevel.lowercase(Locale.ROOT)) {
                        "high", "critical" -> showWarningBanner("Risk: ${verdict.riskLevel}. ${verdict.summary}")
                        "low", "medium" -> showWarningBanner("Safe: ${verdict.summary}")
                        else -> showWarningBanner("Caution: ${verdict.summary}")
                    }
                    showToast("Scan complete: ${verdict.riskLevel}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Scan failed", t)
                showToast("Scan failed: ${t.message ?: "unknown error"}")
            }
        }
    }

    private fun collectScreenContent(root: AccessibilityNodeInfo): String {
        val texts = mutableSetOf<String>()
        collectNodeText(root, texts)
        val all = texts.joinToString("\n")
        val links = Regex("""https?://[^\s)]+""").findAll(all).map { it.value }.toSet()
        return buildString {
            appendLine("Visible text and claims:")
            appendLine(all.take(6000))
            appendLine()
            appendLine("Detected links:")
            if (links.isEmpty()) appendLine("None")
            links.forEach { appendLine(it) }
        }
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, sink: MutableSet<String>) {
        node ?: return
        runCatching {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sink.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sink.add(it) }
            for (i in 0 until node.childCount) {
                collectNodeText(node.getChild(i), sink)
            }
        }.onFailure {
            Log.w(TAG, "Skipping inaccessible node", it)
        }
    }

    private suspend fun captureScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onFailure(errorCode: Int) {
                            executor.shutdown()
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val result = runCatching {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                val encoded = bitmap?.let { bmp ->
                                    val out = ByteArrayOutputStream()
                                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                                        ?.compress(Bitmap.CompressFormat.PNG, 85, out)
                                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                                }
                                hardwareBuffer.close()
                                encoded
                            }.getOrNull()
                            executor.shutdown()
                            if (cont.isActive) cont.resume(result)
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Screenshot capture unavailable", t)
                executor.shutdown()
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun showWarningBanner(message: String) {
        val wm = windowManager ?: return
        if (warningBanner == null) {
            warningBanner = TextView(this).apply {
                setPadding(24, 18, 24, 18)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCCB71C1C.toInt())
                textSize = 14f
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }
            runCatching { wm.addView(warningBanner, params) }
                .onFailure { Log.w(TAG, "Unable to attach warning banner", it) }
        }
        warningBanner?.text = message
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}

private data class RiskVerdict(
    val riskLevel: String,
    val summary: String
)

private object GeminiVerifier {
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    private val MODEL_CANDIDATES = listOf(
        "gemini-flash-latest",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-flash-latest",
        "gemini-1.5-flash"
    )

    fun verify(scanText: String, screenshotBase64: String?, apiKey: String): RiskVerdict {
        return runCatching {
            val prompt = """
                You are a security and misinformation analyst.
                Analyze the user's visible screen content and classify risk.
                Detect:
                - misleading or false claims
                - deceptive manipulation
                - phishing attempts
                - harmful or suspicious websites
                Return strict JSON only:
                {"riskLevel":"low|medium|high|critical","summary":"short explanation"}
                Screen content:
                $scanText
            """.trimIndent()

            val parts = JSONArray().put(JSONObject().put("text", prompt))
            if (!screenshotBase64.isNullOrBlank()) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/png")
                            .put("data", screenshotBase64)
                    )
                )
            }

            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", 0.1)
                )

            var lastUnknown: RiskVerdict? = null
            for (model in MODEL_CANDIDATES) {
                val response = callGenerateContent(model, body, apiKey)
                val parsed = parseVerdict(response)
                when (parsed.riskLevel.lowercase(Locale.ROOT)) {
                    "unknown" -> {
                        lastUnknown = parsed
                        val lowerSummary = parsed.summary.lowercase(Locale.ROOT)
                        val quotaExceeded =
                            "quota" in lowerSummary || "rate limit" in lowerSummary || "resource_exhausted" in lowerSummary
                        if (quotaExceeded) {
                            return FreeSafetyVerifier.verify(scanText, FACTCHECK_API_KEY)
                        }
                        val notFoundOrUnsupported =
                            "not found" in lowerSummary || "not supported" in lowerSummary
                        if (notFoundOrUnsupported) continue
                        return parsed
                    }
                    else -> return parsed
                }
            }
            lastUnknown ?: RiskVerdict("unknown", "No compatible Gemini model responded.")
        }.getOrElse {
            RiskVerdict("unknown", "Network or API error while checking content.")
        }
    }

    private fun callGenerateContent(model: String, body: JSONObject, apiKey: String): String {
        val endpoint = "$API_BASE/$model:generateContent"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-goog-api-key", apiKey)
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.doOutput = true

        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseVerdict(response: String): RiskVerdict {
        return try {
            val root = JSONObject(response)
            if (root.has("error")) {
                val errorObj = root.optJSONObject("error")
                val errorMessage = errorObj?.optString("message").orEmpty()
                return RiskVerdict("unknown", "Gemini API error: ${errorMessage.ifBlank { "request failed" }}")
            }

            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return RiskVerdict("unknown", "Gemini returned no candidates.")
            }

            val firstCandidate = candidates.optJSONObject(0)
            val finishReason = firstCandidate?.optString("finishReason").orEmpty()
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")

            val rawText = buildString {
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val txt = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (txt.isNotBlank()) appendLine(txt)
                    }
                }
            }.trim()

            if (rawText.isBlank()) {
                return RiskVerdict(
                    "unknown",
                    "Gemini returned an empty response${if (finishReason.isNotBlank()) " ($finishReason)" else ""}."
                )
            }

            val cleaned = rawText
                .replace("```json", "", ignoreCase = true)
                .replace("```", "")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')

            if (start >= 0 && end > start) {
                val json = cleaned.substring(start, end + 1)
                val verdict = JSONObject(json)
                return RiskVerdict(
                    riskLevel = verdict.optString("riskLevel", "unknown"),
                    summary = verdict.optString("summary", "No summary returned.")
                )
            }

            // Fallback: parse plain text into a conservative verdict.
            return RiskVerdict(
                riskLevel = inferRiskLevelFromText(cleaned),
                summary = cleaned.take(240)
            )
        } catch (_: Exception) {
            RiskVerdict("unknown", "Unable to parse Gemini response.")
        }
    }

    private fun inferRiskLevelFromText(text: String): String {
        val t = text.lowercase(Locale.ROOT)
        return when {
            "critical" in t -> "critical"
            Regex("""\bhigh\b""").containsMatchIn(t) -> "high"
            Regex("""\bmedium\b""").containsMatchIn(t) -> "medium"
            Regex("""\blow\b""").containsMatchIn(t) -> "low"
            else -> "unknown"
        }
    }
}

private object FreeSafetyVerifier {
    private const val FACT_CHECK_ENDPOINT =
        "https://factchecktools.googleapis.com/v1alpha1/claims:search"

    fun verify(scanText: String, factCheckApiKey: String): RiskVerdict {
        val heuristics = localHeuristicRisk(scanText)
        val factCheck = queryFactCheckApi(scanText, factCheckApiKey)

        val finalRisk = maxRisk(heuristics.riskLevel, factCheck?.riskLevel ?: "low")
        val summary = buildString {
            append(heuristics.summary)
            if (factCheck != null) {
                append(" ")
                append(factCheck.summary)
            } else if (factCheckApiKey.isBlank()) {
                append(" Add FACTCHECK_API_KEY in local.properties for live external fact-check matches.")
            }
        }.trim()

        return RiskVerdict(finalRisk, summary)
    }

    private fun localHeuristicRisk(scanText: String): RiskVerdict {
        val lower = scanText.lowercase(Locale.ROOT)
        val links = Regex("""https?://[^\s)]+""").findAll(scanText).map { it.value }.toList()
        var score = 0
        val reasons = mutableListOf<String>()

        val urgentPatterns = listOf("urgent", "act now", "verify account", "password expired", "otp", "bank", "wallet")
        if (urgentPatterns.any { it in lower }) {
            score += 2
            reasons += "urgent/account-pressure language detected"
        }

        val fakeClaimPatterns = listOf("miracle cure", "guaranteed", "100% true", "secret government")
        if (fakeClaimPatterns.any { it in lower }) {
            score += 2
            reasons += "strong misinformation-style claim phrasing detected"
        }

        links.forEach { url ->
            val u = url.lowercase(Locale.ROOT)
            if ("xn--" in u || Regex("""https?://\d+\.\d+\.\d+\.\d+""").containsMatchIn(u)) {
                score += 3
                reasons += "suspicious link format ($url)"
            }
            if (listOf(".zip", ".top", ".xyz", ".click").any { u.contains(it) }) {
                score += 2
                reasons += "high-risk domain suffix in link ($url)"
            }
            if (listOf("login", "verify", "secure", "update", "wallet", "bank").any { u.contains(it) }) {
                score += 1
                reasons += "credential-themed path in link ($url)"
            }
        }

        val risk = when {
            score >= 6 -> "high"
            score >= 3 -> "medium"
            else -> "low"
        }
        val summary = if (reasons.isEmpty()) {
            "No major phishing or deception heuristics triggered."
        } else {
            reasons.distinct().take(3).joinToString("; ")
        }
        return RiskVerdict(risk, summary)
    }

    private fun queryFactCheckApi(scanText: String, apiKey: String): RiskVerdict? {
        if (apiKey.isBlank()) return null
        val query = extractLikelyClaim(scanText)
        if (query.isBlank()) return null

        return runCatching {
            val url = "$FACT_CHECK_ENDPOINT?query=${encode(query)}&languageCode=en&key=$apiKey"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000

            val response = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } finally {
                conn.disconnect()
            }

            val root = JSONObject(response)
            val claims = root.optJSONArray("claims")
            if (claims == null || claims.length() == 0) {
                return@runCatching RiskVerdict("low", "No matching entries found in public fact-check index.")
            }

            var foundNegative = false
            var foundMixed = false
            for (i in 0 until claims.length()) {
                val claim = claims.optJSONObject(i) ?: continue
                val reviews = claim.optJSONArray("claimReview") ?: continue
                for (j in 0 until reviews.length()) {
                    val review = reviews.optJSONObject(j) ?: continue
                    val textualRating = review.optString("textualRating").lowercase(Locale.ROOT)
                    if (listOf("false", "pants on fire", "misleading", "scam").any { it in textualRating }) {
                        foundNegative = true
                    } else if (listOf("mixed", "partly", "unproven", "unverified").any { it in textualRating }) {
                        foundMixed = true
                    }
                }
            }

            when {
                foundNegative -> RiskVerdict("high", "Public fact-check records include false/misleading ratings for similar claims.")
                foundMixed -> RiskVerdict("medium", "Public fact-check records show mixed or unverified ratings for similar claims.")
                else -> RiskVerdict("low", "Public fact-check records do not show strong negative ratings for similar claims.")
            }
        }.getOrNull()
    }

    private fun extractLikelyClaim(scanText: String): String {
        val lines = scanText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }
            .sortedByDescending { it.length }
        return lines.firstOrNull()?.take(180).orEmpty()
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun maxRisk(a: String, b: String): String {
        fun level(r: String): Int = when (r.lowercase(Locale.ROOT)) {
            "critical" -> 4
            "high" -> 3
            "medium" -> 2
            "low" -> 1
            else -> 0
        }
        return if (level(a) >= level(b)) a else b
    }
}
