package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Text contract for the public release identity and signing boundary. */
class ReleaseConfigContractTest {
    private val build = File("build.gradle.kts").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun release_build_uses_public_identity_and_version() {
        assertTrue(build.contains("namespace = \"dev.chinchillacam.usbprobe\""))
        assertTrue(build.contains("applicationId = \"io.github.oyhamburo.chinchillacam\""))
        assertTrue(build.contains("versionCode = 1"))
        assertTrue(build.contains("versionName = \"0.1.0\""))
        assertTrue(Regex("""<application\b[^>]*android:label="ChinchillaCam"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(manifest))
        assertTrue(Regex("""<activity\s+android:name="\.ConnectionActivity"[^>]*android:label="ChinchillaCam"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(manifest))
    }

    @Test
    fun release_signing_uses_only_the_four_environment_variables() {
        val names = Regex("""System\.getenv\("(CHINCHILLACAM_[A-Z_]+)"\)""")
            .findAll(build).map { it.groupValues[1] }.toList()
        assertEquals(
            setOf("CHINCHILLACAM_KEYSTORE", "CHINCHILLACAM_KEYSTORE_PASSWORD", "CHINCHILLACAM_KEY_ALIAS", "CHINCHILLACAM_KEY_PASSWORD"),
            names.toSet(),
        )
        assertEquals("Each signing input must be read once", 4, names.size)
        assertTrue(build.contains("signingConfigs.create(\"release\")"))
        for (assignment in listOf(
            "storeFile = file(requireNotNull(releaseKeystore))",
            "storePassword = releaseKeystorePassword",
            "keyAlias = releaseKeyAlias",
            "keyPassword = releaseKeyPassword",
            "if (hasReleaseSigning) signingConfig = releaseSigning",
        )) assertTrue("Missing release signing binding: $assignment", build.contains(assignment))
        assertFalse("No debug signing fallback for release", Regex("""signingConfigs\.(?:getByName|named)\("debug"\)""").containsMatchIn(build))
        assertFalse("No keystore paths in the build", Regex("""(?i)(?:\.jks|\.keystore|/Users/|/home/)""").containsMatchIn(build))
        assertFalse("No literal signing passwords", Regex("""(?i)(?:storePassword|keyPassword)\s*=\s*"""").containsMatchIn(build))
    }

    @Test
    fun release_packaging_requires_all_signing_inputs_without_blocking_debug() {
        assertTrue(build.contains("Faltan las variables CHINCHILLACAM_* para firmar el release; ver docs/release.md"))
        assertTrue(build.contains("tasks.matching { it.name in setOf(\"packageRelease\", \"assembleRelease\", \"bundleRelease\") }"))
        assertTrue(
            "Missing variables must fail inside the release task, not while configuring debug",
            Regex("""doFirst\s*\{\s*if \(!hasReleaseSigning\)\s*\{\s*throw GradleException\("Faltan las variables CHINCHILLACAM_\*""")
                .containsMatchIn(build),
        )
    }
}
