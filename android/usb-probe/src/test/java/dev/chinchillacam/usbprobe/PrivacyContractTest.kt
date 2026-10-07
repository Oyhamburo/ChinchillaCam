package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Guards the local-only privacy boundary against changes to the manifest, sources or runtime deps. */
class PrivacyContractTest {
    @Test
    fun manifest_requests_only_allowed_permissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals("Manifest permissions must match the closed allowlist", ALLOWED_PERMISSIONS, permissions(manifest))
    }

    @Test
    fun privacy_guards_detect_violations_without_matching_similar_names() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val withInternet = manifest.replace(
            "</manifest>",
            "<uses-permission android:name=\"android.permission.INTERNET\" /></manifest>",
        )
        assertTrue("XML parser must detect INTERNET", permissions(withInternet) != ALLOWED_PERMISSIONS)
        assertEquals("URLEncoder is not URL", emptyList<String>(), matches("import java.net.URLEncoder", PRIVACY_APIS))
        for (api in listOf(
            "import android.media.MediaRecorder", "import android.media.AudioRecord",
            "import java.net.URL", "import java.net.HttpURLConnection", "import okhttp3.OkHttpClient",
            "import android.webkit.WebView", "getExternalFilesDir(null)",
            "Environment.getExternalStorageDirectory()", "import android.util.Log", "println(value)",
        )) {
            assertTrue("Guard missed $api", matches(api, PRIVACY_APIS).isNotEmpty())
        }
        for (socket in listOf("Socket(\"localhost\", 443)", "import javax.net.ssl.SSLSocket", "it.connect(InetSocketAddress(host, port))")) {
            assertTrue("Guard missed $socket", matches(socket, OUTBOUND_SOCKETS).isNotEmpty())
        }
        assertEquals("Socketless is not Socket", emptyList<String>(), matches("SocketlessChannel()", OUTBOUND_SOCKETS))
        assertEquals(
            "Foreign implementation dependency must be parsed",
            "com.squareup.okhttp3:okhttp",
            runtimeCoordinate("implementation(\"com.squareup.okhttp3:okhttp:4.0\")"),
        )
    }

    @Test
    fun backup_is_disabled() {
        val application = parseManifest(File("src/main/AndroidManifest.xml").readText())
            .getElementsByTagName("application").item(0) as Element
        assertEquals("Application backup must stay disabled", "false", application.getAttributeNS(ANDROID_NS, "allowBackup"))
    }

    @Test
    fun only_connection_activity_is_exported() {
        val application = parseManifest(File("src/main/AndroidManifest.xml").readText())
            .getElementsByTagName("application").item(0) as Element
        val components = listOf("activity", "activity-alias", "service", "receiver", "provider")
            .flatMap { tag ->
                val nodes = application.getElementsByTagName(tag)
                (0 until nodes.length).map { nodes.item(it) as Element }
            }
        val missing = components.filterNot { it.hasAttributeNS(ANDROID_NS, "exported") }
            .map { it.getAttributeNS(ANDROID_NS, "name") }
        assertTrue("Every component must explicitly declare android:exported: $missing", missing.isEmpty())
        val exported = components.filter { it.getAttributeNS(ANDROID_NS, "exported") == "true" }
            .map { it.getAttributeNS(ANDROID_NS, "name") }.toSet()
        assertEquals("Only ConnectionActivity may be exported", setOf(".ConnectionActivity"), exported)
    }

    @Test
    fun main_sources_use_no_forbidden_privacy_apis() {
        val offenders = sourceOffenders(PRIVACY_APIS)
        assertTrue("Forbidden privacy APIs (file:line:token): $offenders", offenders.isEmpty())
    }

    @Test
    fun main_sources_open_no_outbound_sockets() {
        val offenders = sourceOffenders(OUTBOUND_SOCKETS)
        assertTrue("Outbound socket clients in production (file:line:token): $offenders", offenders.isEmpty())
    }

    @Test
    fun runtime_dependencies_are_only_zxing_core() {
        val declarations = File("build.gradle.kts").readLines().mapIndexedNotNull { index, line ->
            if (!RUNTIME_DECLARATION.containsMatchIn(line)) return@mapIndexedNotNull null
            (index + 1) to runtimeCoordinate(line)
        }
        val offenders = declarations.filter { it.second !in ALLOWED_RUNTIME_DEPENDENCIES }
        assertTrue("Runtime dependencies outside allowlist (build.gradle.kts:line:group:artifact): $offenders", offenders.isEmpty())
        assertEquals("Expected the ZXing runtime dependency", ALLOWED_RUNTIME_DEPENDENCIES, declarations.mapNotNull { it.second }.toSet())
    }

    private fun sourceOffenders(patterns: List<Pair<String, Regex>>): List<String> {
        val root = File("src/main/java")
        assertTrue("Debe existir src/main/java", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file ->
                file.readLines().asSequence().flatMapIndexed { index, line ->
                    matches(line, patterns).asSequence().map { name -> "${file.path}:${index + 1}:$name" }
                }
            }.toList()
    }

    private fun matches(line: String, patterns: List<Pair<String, Regex>>): List<String> =
        patterns.filter { (_, regex) -> regex.containsMatchIn(line) }.map { it.first }

    private fun runtimeCoordinate(line: String): String? =
        RUNTIME_COORDINATE.find(line)?.groupValues?.get(1)?.substringBeforeLast(':')

    private fun permissions(xml: String): Set<String> {
        val nodes = parseManifest(xml).getElementsByTagName("uses-permission")
        val names = (0 until nodes.length).map {
            (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name")
        }
        assertEquals("Duplicate manifest permissions", names.size, names.toSet().size)
        return names.toSet()
    }

    private fun parseManifest(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(xml.byteInputStream())

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val ALLOWED_PERMISSIONS = setOf(
            "android.permission.CAMERA",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
        )
        val ALLOWED_RUNTIME_DEPENDENCIES = setOf("com.google.zxing:core")
        val RUNTIME_DECLARATION = Regex("""^\s*(?:implementation|api)\s*\(""")
        val RUNTIME_COORDINATE = Regex("""^\s*(?:implementation|api)\s*\(\s*"([^"]+)"""")
        val PRIVACY_APIS = listOf(
            "MediaRecorder" to Regex("""\b(?:android\.media\.)?MediaRecorder\b"""),
            "AudioRecord" to Regex("""\b(?:android\.media\.)?AudioRecord\b"""),
            "URL" to Regex("""\b(?:java\.net\.)?URL\b"""),
            "HttpURLConnection" to Regex("""\b(?:java\.net\.)?HttpURLConnection\b"""),
            "okhttp" to Regex("""\bokhttp(?:3)?\b"""),
            "WebView" to Regex("""\b(?:android\.webkit\.)?WebView\b"""),
            "getExternalFilesDir" to Regex("""\bgetExternalFilesDir\s*\("""),
            "Environment.getExternalStorage" to Regex("""\bEnvironment\s*\.\s*getExternalStorage\w*\s*\("""),
            "android.util.Log" to Regex("""\b(?:android\.util\.Log|Log\s*\.\s*(?:v|d|i|w|e|wtf|println)\s*\()"""),
            "println" to Regex("""\bprintln\s*\("""),
        )
        val OUTBOUND_SOCKETS = listOf(
            "Socket(" to Regex("""\bSocket\s*\("""),
            "SSLSocket" to Regex("""\bSSLSocket\b"""),
            "connect(InetSocketAddress" to Regex("""\bconnect\s*\(\s*InetSocketAddress\s*\("""),
        )
    }
}
