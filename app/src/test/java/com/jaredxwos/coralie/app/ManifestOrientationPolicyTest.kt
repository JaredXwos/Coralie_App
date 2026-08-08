package com.jaredxwos.coralie.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestOrientationPolicyTest {
    @Test
    fun everyDeclaredActivityIsFixedToPortrait() {
        val manifest = findMainManifest()
        val document =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest)
        val activities = document.getElementsByTagName("activity")

        assertTrue("The app manifest must declare at least one activity", activities.length > 0)
        for (index in 0 until activities.length) {
            val activity = activities.item(index)
            val name = activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name")?.nodeValue

            assertEquals(
                "Activity $name must remain portrait-only in the app manifest",
                "portrait",
                activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "screenOrientation")?.nodeValue,
            )
        }
    }

    private fun findMainManifest(): File {
        val candidates =
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("app/src/main/AndroidManifest.xml"),
            )

        return candidates.firstOrNull(File::isFile)
            ?: error("Could not find app/src/main/AndroidManifest.xml from ${File(".").canonicalPath}")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
