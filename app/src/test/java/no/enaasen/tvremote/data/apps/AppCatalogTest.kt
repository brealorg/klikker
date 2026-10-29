package no.enaasen.tvremote.data.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogTest {

    @Test
    fun knownAppsGetTheirName() {
        assertEquals("NRK TV", AppCatalog.label("no.nrk.tv"))
        assertEquals("Netflix", AppCatalog.label("com.netflix.ninja"))
    }

    @Test
    fun unknownAppsGetANameFromThePackage() {
        assertEquals("Example", AppCatalog.label("com.example.player"))
        assertEquals("Rikstv", AppCatalog.derivedLabel("no.rikstv.atv"))
        assertEquals("Settings", AppCatalog.derivedLabel("com.android.tv.settings"))
        assertEquals("Tv", AppCatalog.derivedLabel("com.tv"))
    }

    @Test
    fun launchersAndSystemScreensAreNotSuggested() {
        assertFalse(AppCatalog.isSuggestible("com.google.android.tvlauncher"))
        assertFalse(AppCatalog.isSuggestible("com.android.tv.settings"))
        assertFalse(AppCatalog.isSuggestible("com.spocky.projengmenu"))
        assertTrue(AppCatalog.isSuggestible("no.nrk.tv"))
        assertTrue(AppCatalog.isSuggestible("com.google.android.youtube.tv"))
    }

    @Test
    fun manifestLetsTheAppSeeEveryIconPackage() {
        // Unit tests run from the module directory.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<package android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), AppCatalog.iconPackagesOfKnownApps - declared)
    }

    @Test
    fun appsOpenThroughTheirOwnLinkOrTheirStorePage() {
        assertEquals("nrktv://", AppCatalog.launchLink("no.nrk.tv"))
        assertEquals("market://details?id=org.jellyfin.androidtv", AppCatalog.launchLink("org.jellyfin.androidtv"))
    }
}
