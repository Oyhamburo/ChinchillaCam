package dev.chinchillacam.usbprobe

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guardia de caracterización T16/w3: el transporte Wi‑Fi fake es un stream en memoria y
 * ningún código de producción abre un listener ni declara permisos de red. Falla si el
 * manifest declara un permiso de red o si cualquier fuente de `src/main/java` usa una API de
 * socket/descubrimiento de servidor. Las fuentes y el manifest se leen de disco con la misma
 * base relativa al módulo que usa `VisibleCameraForegroundServiceContractTest`.
 */
class NoNetworkListenerContractTest {
    @Test
    fun manifestDeclaresNoInternetPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        val forbidden = FORBIDDEN_NETWORK_PERMISSIONS.firstOrNull { manifest.contains(it) }
        assertNull(
            "El manifest no debe declarar permisos de red (encontrado: $forbidden)",
            forbidden,
        )
    }

    @Test
    fun mainSourcesOpenNoServerSocket() {
        val root = File("src/main/java")
        assertTrue("Debe existir src/main/java", root.isDirectory)

        val offenders = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapNotNull { file ->
                firstForbiddenNetworkToken(file.readText())?.let { token -> "${file.path}: $token" }
            }
            .toList()

        assertTrue(
            "Ninguna fuente de producción debe usar sockets de servidor ni descubrimiento de red: $offenders",
            offenders.isEmpty(),
        )
    }

    private companion object {
        val FORBIDDEN_NETWORK_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        )

        val FORBIDDEN_NETWORK_TOKENS = listOf(
            "ServerSocket",
            "ServerSocketChannel",
            "DatagramSocket",
            "MulticastSocket",
            "NsdManager",
        )

        fun firstForbiddenNetworkToken(source: String): String? =
            FORBIDDEN_NETWORK_TOKENS.firstOrNull { source.contains(it) }
    }
}
