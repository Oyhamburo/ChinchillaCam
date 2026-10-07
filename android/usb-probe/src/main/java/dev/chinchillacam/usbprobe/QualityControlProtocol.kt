package dev.chinchillacam.usbprobe

/** Frame 7 vocabulary shared with the desktop; unknown arguments are ignored for forward compatibility. */
const val QUALITY_CONTROL_CAPABILITY = "quality-control-v1"
const val QUALITY_SUBSCRIBE = "quality_subscribe"
const val QUALITY_STATE = "quality_state"
const val SET_QUALITY = "set_quality"

private const val MAX_OPTIONS = 16
private const val MAX_BYTES = 256
private val POSITIVE_DECIMAL = Regex("[1-9][0-9]*")

/** A valid request carries an optional camera change; null means preserve the current selection. */
sealed class SetQualityRequest {
    data class Valid(val req: Long, val camera: String?, val preference: QualityPreference) : SetQualityRequest()
    data class Invalid(val req: Long?) : SetQualityRequest()
}

private fun withinLimit(arguments: Map<String, String>): Boolean = arguments.all { (key, value) ->
    key.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES
}

private fun positiveLong(value: String?): Long? = value?.takeIf { POSITIVE_DECIMAL.matches(it) }?.toLongOrNull()
private fun positiveInt(value: String?): Int? = value?.takeIf { POSITIVE_DECIMAL.matches(it) }?.toIntOrNull()

fun isSubscribe(command: String, args: Map<String, String>): Boolean =
    command == QUALITY_SUBSCRIBE && withinLimit(args) && args["v"] == "1"

/** Invalid requests are represented explicitly so the handler can echo a valid req in its error state. */
fun parseSetQuality(arguments: Map<String, String>): SetQualityRequest {
    val req = positiveLong(arguments["req"])
    if (!withinLimit(arguments) || arguments["v"] != "1" || req == null) return SetQualityRequest.Invalid(req)
    val camera = arguments["camera"]
    if (camera != null && camera.isEmpty()) return SetQualityRequest.Invalid(req)
    val preference = when (arguments["mode"]) {
        "auto" -> QualityPreference.Automatic
        "manual" -> {
            val width = positiveInt(arguments["width"])
            val height = positiveInt(arguments["height"])
            val fps = positiveInt(arguments["fps"])
            if (width == null || height == null || fps == null) return SetQualityRequest.Invalid(req)
            QualityPreference.Manual(width, height, fps)
        }
        else -> return SetQualityRequest.Invalid(req)
    }
    return SetQualityRequest.Valid(req, camera, preference)
}

/** UTF-8 byte prefix ending at a complete code point (never split an accented letter or emoji). */
private fun limited(value: String): String {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val length = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (bytes + length > MAX_BYTES) break
        bytes += length
        index += Character.charCount(codePoint)
    }
    return value.substring(0, index)
}

/** Encode the selected AUTO sentinel separately; camera.* contains only direct camera IDs. */
fun encodeQualityState(plan: QualityControlsPlan, req: Long?, error: String?): Map<String, String> {
    val result = linkedMapOf(
        "v" to "1",
        "mode" to if (plan.appliedPreference is QualityPreference.Manual) "manual" else "auto",
        "camera.selected" to limited(plan.cameras.firstOrNull { it.selected }?.id ?: "auto"),
    )
    if (req != null) result["req"] = req.toString()
    if (error != null) result["error"] = limited(error)
    val cameras = plan.cameras.filter { it.id != null }.take(MAX_OPTIONS)
    result["camera.count"] = cameras.size.toString()
    cameras.forEachIndexed { i, choice ->
        result["camera.$i.id"] = limited(choice.id!!)
        result["camera.$i.label"] = limited(choice.label)
    }
    val resolutions = plan.resolutions.take(MAX_OPTIONS)
    result["res.count"] = resolutions.size.toString()
    resolutions.forEachIndexed { i, choice ->
        result["res.$i"] = "${choice.value.width}x${choice.value.height}"
        result["res.$i.enabled"] = if (choice.enabled) "1" else "0"
        if (!choice.enabled && choice.disabledReason != null) result["res.$i.reason"] = limited(choice.disabledReason)
    }
    val rates = plan.frameRates.take(MAX_OPTIONS)
    result["fps.count"] = rates.size.toString()
    rates.forEachIndexed { i, choice ->
        result["fps.$i"] = choice.value.toString()
        result["fps.$i.enabled"] = if (choice.enabled) "1" else "0"
        if (!choice.enabled && choice.disabledReason != null) result["fps.$i.reason"] = limited(choice.disabledReason)
    }
    result["applied.res"] = "${plan.appliedResolution.width}x${plan.appliedResolution.height}"
    result["applied.fps"] = plan.appliedFps.toString()
    result["summary"] = limited(plan.summary)
    return result
}
