package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import java.io.File
import java.io.StringReader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Verify-after-act for the mutating UI tools (Shaheen 3, VISION_PLAN_SHAHEEN3.md §4).
 *
 * Doctrine: Shaheen checks the screen after acting and reports honestly — a tool's own
 * "success" is never passed to the model as proof that the action took effect.
 *
 * How it hooks in: [AIToolHandler.registerTool] wraps the executor of every tool whose
 * registered name is in [VERIFIED_TOOL_NAMES] with [VerifyingExecutor]. Registration is the
 * single choke point shared by every execution route — the chat loop
 * (ToolExecutionManager resolves executors from the handler registry and calls
 * invokeAndStream on them directly), AIToolHandler.executeTool / executeToolAndStream,
 * package/MCP executors, workflows and the JS bridge. (PhoneAgent's direct
 * ToolImplementations calls bypass the registry; that loop re-observes the screen itself
 * via its next-step screenshot.)
 *
 * Mechanism: snapshot the UI hierarchy (UIHierarchyManager AIDL, the same channel the UI
 * tools use) immediately before the action, run the tool unchanged, snapshot again, and
 * compare — foreground package, hierarchy content, and per-tool expectations derived from
 * the call parameters. The verdict is appended as a short "Verification: ..." block to a
 * COPY of the ToolResult (the original object and the tool's own success flag are never
 * altered), so it reaches the model through the normal result-formatting path. A
 * screenshot is taken only when verification FAILED, and referenced with the same
 * <link type="image" id="..."></link> pattern other tools use.
 *
 * Verification is advisory by design and MUST fail open: it never blocks, fails, or
 * meaningfully delays a tool call. Any error, timeout, or unreachable accessibility
 * provider degrades to the unmodified result (plus at most an "unavailable" note). This
 * is a hard design requirement of the verify stage, not an optional safety net.
 */
object UiActionVerifier {
    private const val TAG = "UiActionVerifier"

    /** Only the mutating UI-action tools are verified. Read tools are never verified. */
    val VERIFIED_TOOL_NAMES: Set<String> =
            setOf("click_element", "tap", "set_input_text", "press_key", "swipe")

    /** Bounds a single hierarchy read so an unbound provider cannot stall execution. */
    private const val SNAPSHOT_TIMEOUT_MS = 1500L
    private const val SCREENSHOT_TIMEOUT_MS = 2000L

    /**
     * After a failed state read, skip verification attempts for this long (passing results
     * through silently) so a setup without the accessibility provider does not pay the
     * capped read attempt on every single UI action.
     */
    private const val UNAVAILABLE_COOLDOWN_MS = 30_000L

    @Volatile private var lastUnavailableAtMs: Long = 0L

    fun isVerifiable(toolName: String): Boolean = toolName in VERIFIED_TOOL_NAMES

    /** Wrap [executor] if [toolName] is in scope; idempotent (never double-wraps). */
    fun wrapIfVerifiable(toolName: String, executor: ToolExecutor, context: Context): ToolExecutor {
        if (!isVerifiable(toolName)) return executor
        if (executor is VerifyingExecutor) return executor
        return VerifyingExecutor(executor, context)
    }

    private data class Snapshot(val hierarchyXml: String, val foregroundPackage: String?)

    private sealed interface CaptureOutcome {
        /** Inside the unavailability cooldown — do not even attempt a read. */
        data object SkippedCooldown : CaptureOutcome
        /** A read was attempted and produced nothing usable. */
        data object Unavailable : CaptureOutcome
        data class Captured(val snapshot: Snapshot) : CaptureOutcome
    }

    private class VerifyingExecutor(
            private val delegate: ToolExecutor,
            private val context: Context
    ) : ToolExecutor {

        override fun validateParameters(tool: AITool): ToolValidationResult {
            return delegate.validateParameters(tool)
        }

        override fun invoke(tool: AITool): ToolResult {
            val pre = runBlocking(Dispatchers.IO) { captureSnapshot() }
            val result = delegate.invoke(tool)
            if (!result.success) return result
            return runBlocking(Dispatchers.IO) { verifyAndAugment(tool, pre, result) }
        }

        override fun invokeAndStream(tool: AITool): Flow<ToolResult> = flow {
            val pre = captureSnapshot()
            var pending: ToolResult? = null
            try {
                delegate.invokeAndStream(tool).collect { streamed ->
                    // Hold back the latest result: only the final one is verified, and it
                    // is emitted (verified) after the stream completes. Earlier results
                    // pass through unmodified, one emission behind.
                    pending?.let { emit(it) }
                    pending = streamed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending?.let { emit(it) }
                throw e
            }
            val last = pending
            if (last != null) {
                emit(if (last.success) verifyAndAugment(tool, pre, last) else last)
            }
        }

        private suspend fun captureSnapshot(): CaptureOutcome {
            val now = System.currentTimeMillis()
            if (now - lastUnavailableAtMs < UNAVAILABLE_COOLDOWN_MS) {
                return CaptureOutcome.SkippedCooldown
            }
            return try {
                val xml = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
                    UIHierarchyManager.getUIHierarchy(context)
                }
                if (xml.isNullOrEmpty()) {
                    lastUnavailableAtMs = System.currentTimeMillis()
                    CaptureOutcome.Unavailable
                } else {
                    lastUnavailableAtMs = 0L
                    CaptureOutcome.Captured(
                            Snapshot(xml, UIHierarchyManager.extractWindowInfo(xml).first)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "UI state capture failed for verification", e)
                lastUnavailableAtMs = System.currentTimeMillis()
                CaptureOutcome.Unavailable
            }
        }

        /**
         * Compare pre/post state for [tool] and return a copy of [result] with the verdict
         * appended. Fails open: any internal error returns [result] unmodified.
         */
        private suspend fun verifyAndAugment(
                tool: AITool,
                pre: CaptureOutcome,
                result: ToolResult
        ): ToolResult {
            return try {
                when (pre) {
                    CaptureOutcome.SkippedCooldown -> result
                    CaptureOutcome.Unavailable ->
                            augment(
                                    result,
                                    "Verification: unavailable — the UI state could not be " +
                                            "read before the action (accessibility provider " +
                                            "unreachable), so this action is UNVERIFIED."
                            )
                    is CaptureOutcome.Captured -> {
                        val postOutcome = captureSnapshot()
                        if (postOutcome !is CaptureOutcome.Captured) {
                            augment(
                                    result,
                                    "Verification: unavailable — the UI state could not be " +
                                            "read after the action, so its effect is UNVERIFIED."
                            )
                        } else {
                            val verdict = evaluate(tool, pre.snapshot, postOutcome.snapshot)
                            var block = verdict.text
                            if (!verdict.passed) {
                                captureFailureScreenshot(tool)?.let { path ->
                                    block += "\n<link type=\"image\" id=\"$path\"></link>"
                                }
                            }
                            augment(result, block)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fail open by design: verification must never break the tool call it observes.
                AppLogger.w(TAG, "Verification failed open for ${tool.name}", e)
                result
            }
        }

        private fun augment(result: ToolResult, verificationBlock: String): ToolResult {
            val combined = result.result.toString().trimEnd() + "\n\n" + verificationBlock
            return result.copy(result = StringResultData(combined))
        }

        private data class Verdict(val passed: Boolean, val text: String)

        private fun evaluate(tool: AITool, pre: Snapshot, post: Snapshot): Verdict {
            return when (tool.name) {
                "set_input_text" -> evaluateSetInputText(tool, post)
                else -> evaluateVisibleChange(tool, pre, post)
            }
        }

        /**
         * set_input_text expectation: after the action, the focused (or any editable) field
         * in the post-action hierarchy holds the text that was set. Password fields cannot
         * be read back and are reported as UNVERIFIABLE rather than FAILED.
         */
        private fun evaluateSetInputText(tool: AITool, post: Snapshot): Verdict {
            val expected = tool.parameters.find { it.name == "text" }?.value ?: ""
            val nodes = scanNodes(post.hierarchyXml)
            val focused = nodes.firstOrNull { it["focused"] == "true" }
            if (focused != null && focused["password"] == "true") {
                return Verdict(
                        passed = true,
                        text = "Verification: UNVERIFIABLE — the focused field is a password " +
                                "field, so its content cannot be read back to confirm the text."
                )
            }
            val editableNodes =
                    nodes.filter {
                        it["focused"] == "true" ||
                                it["editable"] == "true" ||
                                (it["class"]?.endsWith("EditText") == true)
                    }
            val hit =
                    editableNodes.firstOrNull { node ->
                        val nodeText = node["text"]?.replace("&#10;", "\n")
                        nodeText != null &&
                                (nodeText == expected ||
                                        (expected.isNotEmpty() && nodeText.contains(expected)))
                    }
            return if (hit != null) {
                Verdict(
                        passed = true,
                        text = "Verification: PASSED — the input field now contains the set text."
                )
            } else {
                val focusedText = focused?.get("text")?.replace("&#10;", "\n")
                Verdict(
                        passed = false,
                        text = "Verification: FAILED — the set text was not observed in any " +
                                "editable field after the action (focused field now shows: " +
                                (focusedText?.let { "\"$it\"" } ?: "no focused field found") +
                                "). The tool reported success, but do not assume the text " +
                                "was entered."
                )
            }
        }

        /**
         * click_element / tap / press_key / swipe expectation: the action visibly changed
         * something — the foreground app changed, the hierarchy changed, or (click_element)
         * the target element's own state changed (e.g. a toggle flipping without a redraw
         * of anything else).
         */
        private fun evaluateVisibleChange(tool: AITool, pre: Snapshot, post: Snapshot): Verdict {
            val packageChanged =
                    pre.foregroundPackage != null &&
                            post.foregroundPackage != null &&
                            pre.foregroundPackage != post.foregroundPackage
            val hierarchyChanged = pre.hierarchyXml != post.hierarchyXml
            val targetChanged =
                    tool.name == "click_element" && clickTargetStateChanged(tool, pre, post)
            return when {
                packageChanged ->
                        Verdict(
                                passed = true,
                                text = "Verification: PASSED — the foreground app changed " +
                                        "(${pre.foregroundPackage} -> ${post.foregroundPackage}) " +
                                        "after the action."
                        )
                hierarchyChanged ->
                        Verdict(
                                passed = true,
                                text = "Verification: PASSED — the on-screen UI hierarchy " +
                                        "changed after the action."
                        )
                targetChanged ->
                        Verdict(
                                passed = true,
                                text = "Verification: PASSED — the target element's state " +
                                        "changed after the action."
                        )
                else ->
                        Verdict(
                                passed = false,
                                text = "Verification: FAILED — no visible change was observed " +
                                        "after the action: the foreground app and the UI " +
                                        "hierarchy are unchanged" +
                                        (if (tool.name == "click_element")
                                                ", and the target element's state is unchanged"
                                        else "") +
                                        ". The tool reported success, but the action may not " +
                                        "have taken effect; do not assume it worked."
                        )
            }
        }

        /**
         * Mirrors the node-matching semantics of the click_element implementation
         * (resourceId suffix match, className equality, contentDesc case-insensitive
         * equality; bounds equality when bounds is the selector) and compares the matched
         * node's full attribute set before/after.
         */
        private fun clickTargetStateChanged(tool: AITool, pre: Snapshot, post: Snapshot): Boolean {
            val resourceId = tool.parameters.find { it.name == "resourceId" }?.value
            val className = tool.parameters.find { it.name == "className" }?.value
            val contentDesc = tool.parameters.find { it.name == "contentDesc" }?.value
            val bounds = tool.parameters.find { it.name == "bounds" }?.value
            val index = tool.parameters.find { it.name == "index" }?.value?.toIntOrNull() ?: 0
            if (resourceId == null && className == null && contentDesc == null && bounds == null) {
                return false
            }
            fun matches(attrs: Map<String, String>): Boolean {
                if (resourceId != null &&
                                (attrs["resource-id"] == null ||
                                        !attrs["resource-id"]!!.endsWith(resourceId))) {
                    return false
                }
                if (className != null && attrs["class"] != className) return false
                if (contentDesc != null &&
                                !attrs["content-desc"].equals(contentDesc, ignoreCase = true)) {
                    return false
                }
                if (resourceId == null && className == null && contentDesc == null &&
                                bounds != null && attrs["bounds"] != bounds) {
                    return false
                }
                return true
            }
            val preNode = scanNodes(pre.hierarchyXml).filter(::matches).getOrNull(index)
            val postNode = scanNodes(post.hierarchyXml).filter(::matches).getOrNull(index)
            if (preNode == null) return false
            // Element gone after the action, or any attribute (checked/selected/focused/
            // text/...) differs: the click visibly did something to its target.
            return postNode == null || preNode != postNode
        }

        /** Screenshot on verification failure only; returns the file path or null. */
        private suspend fun captureFailureScreenshot(tool: AITool): String? {
            return try {
                val file =
                        File(
                                OperitPaths.cleanOnExitDir(),
                                "verify_${tool.name}_${System.currentTimeMillis()}.png"
                        )
                val ok =
                        withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                            UIHierarchyManager.takeScreenshot(context, file.absolutePath, "png")
                        }
                if (ok == true) file.absolutePath else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Verification screenshot failed for ${tool.name}", e)
                null
            }
        }

        /** Parse the hierarchy XML into one attribute map per <node>, in document order. */
        private fun scanNodes(xml: String): List<Map<String, String>> {
            val nodes = mutableListOf<Map<String, String>>()
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "node") {
                    val attrs = mutableMapOf<String, String>()
                    for (i in 0 until parser.attributeCount) {
                        val attrName = parser.getAttributeName(i)
                        val attrValue = parser.getAttributeValue(i)
                        if (attrName != null && attrValue != null) attrs[attrName] = attrValue
                    }
                    nodes.add(attrs)
                }
                event = parser.next()
            }
            return nodes
        }
    }
}
