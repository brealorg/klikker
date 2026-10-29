package no.enaasen.tvremote.data.apps

/**
 * Names for apps the TV reports by package name. The remote protocol can't
 * list installed apps, so apps are learned as the TV opens them; this turns
 * "no.nrk.tv" into "NRK TV" and keeps home screens and system UI out.
 */
object AppCatalog {

    fun label(packageName: String): String = KNOWN_APPS[packageName] ?: derivedLabel(packageName)

    /**
     * Phone packages that may carry the TV app's icon: the same package, then
     * the app's phone edition. Each must also be listed under <queries> in
     * the manifest, or Android hides it from this app.
     */
    fun iconPackages(packageName: String): List<String> = listOf(packageName) + PHONE_EDITIONS[packageName].orEmpty()

    /** Every package [iconPackages] can return for a known app. */
    internal val iconPackagesOfKnownApps: Set<String>
        get() = KNOWN_APPS.keys.flatMapTo(mutableSetOf()) { iconPackages(it) }

    /** Whether the app is worth offering as a shortcut, unlike launchers and system screens. */
    fun isSuggestible(packageName: String): Boolean =
        packageName !in NOT_APPS && !packageName.startsWith("com.android.")

    /** "com.example.player" becomes "Example"; generic segments like "com" or "tv" are skipped. */
    internal fun derivedLabel(packageName: String): String {
        val segments = packageName.split('.').filter { it.isNotEmpty() }
        val name = segments.firstOrNull { it.lowercase() !in GENERIC_SEGMENTS } ?: segments.lastOrNull() ?: packageName
        return name.replaceFirstChar { it.uppercase() }
    }

    private val KNOWN_APPS = mapOf(
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.cxinventor.file.explorer" to "File Explorer",
        "com.disney.disneyplus" to "Disney+",
        "com.edde746.plezy" to "Plezy",
        "com.esaba.downloader" to "Downloader",
        "com.google.android.play.games" to "Google Play Games",
        "com.google.android.tv" to "Live Channels",
        "com.google.android.videos" to "Google TV",
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.netflix.ninja" to "Netflix",
        "com.nvidia.tegrazone3" to "GeForce NOW",
        "com.plexapp.android" to "Plex",
        "com.spotify.tv.android" to "Spotify",
        "com.termux" to "Termux",
        "com.valvesoftware.steamlink" to "Steam Link",
        "com.wbd.stream" to "Max",
        "no.nrk.tv" to "NRK TV",
        "no.rikstv.atv" to "RiksTV",
        "org.jellyfin.androidtv" to "Jellyfin",
        "org.smarttube.beta" to "SmartTube",
        "org.smarttube.stable" to "SmartTube",
        "org.videolan.vlc" to "VLC",
        "org.xbmc.kodi" to "Kodi",
    )

    private val PHONE_EDITIONS = mapOf(
        "com.amazon.amazonvideo.livingroom" to listOf("com.amazon.avod.thirdpartyclient"),
        "com.google.android.youtube.tv" to listOf("com.google.android.youtube"),
        "com.google.android.youtube.tvkids" to listOf("com.google.android.apps.youtube.kids"),
        "com.google.android.youtube.tvmusic" to listOf("com.google.android.apps.youtube.music"),
        "com.netflix.ninja" to listOf("com.netflix.mediaclient"),
        "com.nvidia.tegrazone3" to listOf("com.nvidia.geforcenow"),
        "com.spotify.tv.android" to listOf("com.spotify.music"),
        "org.jellyfin.androidtv" to listOf("org.jellyfin.mobile"),
        "org.smarttube.beta" to listOf("org.smarttube.stable"),
    )

    private val NOT_APPS = setOf(
        "com.google.android.apps.tv.dreamx",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.backdrop",
        "com.google.android.katniss",
        "com.google.android.leanbacklauncher",
        "com.google.android.tv.remote.service",
        "com.google.android.tvlauncher",
        "com.google.android.tvrecommendations",
        "com.spocky.projengmenu",
    )

    private val GENERIC_SEGMENTS = setOf(
        "android", "androidtv", "app", "apps", "atv", "com", "de", "dk", "google",
        "io", "leanback", "mobile", "net", "no", "org", "se", "tv", "uk",
    )
}
