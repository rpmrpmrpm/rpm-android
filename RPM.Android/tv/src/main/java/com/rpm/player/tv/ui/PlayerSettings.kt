package com.rpm.player.tv.ui

import com.rpm.player.core.playback.SongRadioRuns
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rpm.player.core.playback.LevelMode
import com.rpm.player.core.playback.Levelling
import com.rpm.player.core.art.OutputPixels
import com.rpm.player.core.room.RoomStyle

/**
 * The settings the TV keeps for the person, shown in the side menu's settings panel: the resolution the screens
 * are drawn at ([ScreenResolution]), the layout the player opens on, whether the performance stats are shown
 * ([StatsOverlay]), and after how many minutes without a key the screens make way for the room ([Screensaver];
 * the default is 2). The default layout is the centred view, the record and its title without the spectrum.
 * The layout key in the player steps through the same setting, so the layout
 * picked there is the one the player opens on next time. The stats are off by default. Volume levelling matches albums
 * by default and leaves records marked classical alone, as on the desktop and the Apple TV.
 *
 * Stored in this box's preferences. Read once, on the main thread, as the app starts; written with
 * `apply()`, so a process killed before the write lands keeps the older choice.
 */
class PlayerSettings(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var defaultView by mutableStateOf(read())
        private set

    var showStats by mutableStateOf(prefs.getBoolean(KEY_STATS, false))
        private set

    fun switchStats() {
        showStats = !showStats
        prefs.edit().putBoolean(KEY_STATS, showStats).apply()
    }

    /** Minutes without a key before the screensaver; 0 is off. One of [SCREENSAVER_CHOICES]. */
    var screensaverMinutes by mutableStateOf(prefs.getInt(KEY_SAVER, SCREENSAVER_DEFAULT).takeIf { it in SCREENSAVER_CHOICES } ?: SCREENSAVER_DEFAULT)
        private set

    /** Moves to the next of [SCREENSAVER_CHOICES], wrapping from the longest to off. */
    fun nextScreensaver() {
        val next = SCREENSAVER_CHOICES[(SCREENSAVER_CHOICES.indexOf(screensaverMinutes) + 1) % SCREENSAVER_CHOICES.size]
        screensaverMinutes = next
        prefs.edit().putInt(KEY_SAVER, next).apply()
    }

    /** How much volume levelling is applied ([LevelMode]), and whether records marked classical are levelled
     * too. Shown here; [levelling] is the copy the playback service's sink reads for every buffer. */
    var levelMode by mutableStateOf(prefs.getString(KEY_LEVEL, null)?.let { name -> LevelMode.entries.firstOrNull { it.name == name } } ?: LevelMode.Album)
        private set

    var levelsClassical by mutableStateOf(prefs.getBoolean(KEY_LEVEL_CLASSICAL, false))
        private set

    val levelling = Levelling(levelMode, levelsClassical)

    /** Moves to the next levelling mode, wrapping from Match tracks to Off. */
    fun nextLevelMode() {
        val next = LevelMode.entries[(levelMode.ordinal + 1) % LevelMode.entries.size]
        levelMode = next
        levelling.mode = next
        prefs.edit().putString(KEY_LEVEL, next.name).apply()
    }

    fun switchLevelsClassical() {
        levelsClassical = !levelsClassical
        levelling.includeClassical = levelsClassical
        prefs.edit().putBoolean(KEY_LEVEL_CLASSICAL, levelsClassical).apply()
    }

    /** The window's height in pixels, and the output's: 1080 and 2160 on the Shield, 1080 and 1080 on the Amiko. */
    val windowHeight: Int = context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
    val outputHeight: Int = Math.round(windowHeight * OutputPixels.share(context))

    /** The resolutions this box offers: Automatic, then each up to the output's height. */
    val resolutionChoices: List<ScreenResolution> =
        ScreenResolution.entries.filter { it.height == null || it.height <= outputHeight }

    /** The resolution shown in the settings panel. Stepping through it changes nothing on screen until
     * [applyResolution], which the panel calls as it closes. */
    var resolution by mutableStateOf(
        prefs.getString(KEY_RESOLUTION, null)?.let { name -> ScreenResolution.entries.firstOrNull { it.name == name } }
            ?.takeIf { it in resolutionChoices } ?: ScreenResolution.Automatic,
    )
        private set

    /** The resolution the screens are drawn at. `MainActivity` recreates the screens when it changes. */
    var appliedResolution by mutableStateOf(resolution)
        private set

    fun nextResolution() = pickResolution(resolutionChoices[(resolutionChoices.indexOf(resolution) + 1) % resolutionChoices.size])

    fun pickResolution(chosen: ScreenResolution) {
        resolution = chosen
        prefs.edit().putString(KEY_RESOLUTION, chosen.name).apply()
    }

    fun applyResolution() {
        appliedResolution = resolution
    }

    /** The height in pixels the screens are drawn at for [appliedResolution]. */
    fun drawnHeight(): Int = appliedResolution.height ?: outputHeight

    /** What the collection screens are drawn on ([PageBackground]); shown at once as it changes. The default is the
     * desktop's backdrop: the moving room belongs to the player. */
    var background by mutableStateOf(prefs.getString(KEY_BACKGROUND, null)?.let { name -> PageBackground.entries.firstOrNull { it.name == name } } ?: PageBackground.Default)
        private set

    fun nextBackground() {
        val next = PageBackground.entries[(background.ordinal + 1) % PageBackground.entries.size]
        background = next
        prefs.edit().putString(KEY_BACKGROUND, next.name).apply()
    }

    /** Whether the albums screen is the carousel ([AlbumCarousel]) rather than the grid; the grid until changed. */
    var albumCarousel by mutableStateOf(prefs.getBoolean(KEY_ALBUM_CAROUSEL, false))
        private set

    fun switchAlbumCarousel() {
        albumCarousel = !albumCarousel
        prefs.edit().putBoolean(KEY_ALBUM_CAROUSEL, albumCarousel).apply()
    }

    /** The order the albums screen opens in ([WallOrder]): the last one picked there or here, kept across launches.
     * The default is by artist, as on the desktop. */
    var albumOrder by mutableStateOf(prefs.getString(KEY_ALBUM_ORDER, null)?.let { name -> WallOrder.entries.firstOrNull { it.name == name } } ?: WallOrder.Artist)
        private set

    fun pickAlbumOrder(order: WallOrder) {
        albumOrder = order
        prefs.edit().putString(KEY_ALBUM_ORDER, order.name).apply()
    }

    fun nextAlbumOrder() = pickAlbumOrder(WallOrder.entries[(albumOrder.ordinal + 1) % WallOrder.entries.size])

    /** Whether a screen, as it opens, shows for two seconds what the remote's keys do on it ([ControlsLegend]); on
     * until switched off, since someone new to the TV is the one who needs it and will not look for it here. */
    var keyLegend by mutableStateOf(prefs.getBoolean(KEY_LEGEND, true))
        private set

    fun switchKeyLegend() {
        keyLegend = !keyLegend
        prefs.edit().putBoolean(KEY_LEGEND, keyLegend).apply()
    }

    /** Where the mini player stands ([MiniPlace]), or Off for none; the default is at the top. */
    var miniPlace by mutableStateOf(prefs.getString(KEY_MINI, null)?.let { name -> MiniPlace.entries.firstOrNull { it.name == name } } ?: MiniPlace.Top)
        private set

    fun nextMiniPlace() {
        miniPlace = MiniPlace.entries[(miniPlace.ordinal + 1) % MiniPlace.entries.size]
        prefs.edit().putString(KEY_MINI, miniPlace.name).apply()
    }

    /** Which animation the room draws behind the player and the screens ([RoomStyle]); the original by
     * default. Read by the room on its next frame, so a change shows while the settings are open. */
    var roomStyle by mutableStateOf(prefs.getString(KEY_ROOM_STYLE, null)?.let { name -> RoomStyle.named(name) } ?: RoomStyle.Original)
        private set

    fun nextRoomStyle() {
        roomStyle = RoomStyle.entries[(roomStyle.ordinal + 1) % RoomStyle.entries.size]
        prefs.edit().putString(KEY_ROOM_STYLE, roomStyle.name).apply()
    }

    /** Whether the sung lyric line lights word by word; off by default. */
    var karaoke by mutableStateOf(prefs.getBoolean(KEY_KARAOKE, false))
        private set

    /** Whether an album, a playlist or a single that ends goes on as a mixtape of songs like it. The playback service
     * reads the stored value when a list's last song starts ([com.rpm.player.core.playback.SongRadioRuns]). */
    var mixtapeAfter by mutableStateOf(prefs.getBoolean(SongRadioRuns.MIXTAPE_AFTER, SongRadioRuns.MIXTAPE_AFTER_DEFAULT))
        private set

    fun switchMixtapeAfter() {
        mixtapeAfter = !mixtapeAfter
        prefs.edit().putBoolean(SongRadioRuns.MIXTAPE_AFTER, mixtapeAfter).apply()
    }

    fun switchKaraoke() {
        karaoke = !karaoke
        prefs.edit().putBoolean(KEY_KARAOKE, karaoke).apply()
    }

    /** Whether the player shows the lyrics, as the player's lyric key last left it; off by default. Kept here and
     * not in the player, which forgets everything when Back leaves it: the lyrics went off every time the person
     * stepped out to the collection and came back. A track without synced lyrics still shows the ordinary view. */
    var lyricsOn by mutableStateOf(prefs.getBoolean(KEY_LYRICS, false))
        private set

    fun switchLyrics() {
        lyricsOn = !lyricsOn
        prefs.edit().putBoolean(KEY_LYRICS, lyricsOn).apply()
    }

    /** How bright and saturated the screens are drawn ([PictureLook]); shown at once as it changes. */
    var picture by mutableStateOf(prefs.getString(KEY_PICTURE, null)?.let { name -> PictureLook.entries.firstOrNull { it.name == name } } ?: PictureLook.Standard)
        private set

    fun nextPicture() = pickPicture(PictureLook.entries[(picture.ordinal + 1) % PictureLook.entries.size])

    fun pickPicture(look: PictureLook) {
        picture = look
        prefs.edit().putString(KEY_PICTURE, look.name).apply()
    }

    /** Moves to the next layout, wrapping, in the order the player's layout key steps through them. */
    fun nextDefaultView() = pickDefaultView(PegasusView.entries[(defaultView.ordinal + 1) % PegasusView.entries.size])

    fun pickDefaultView(view: PegasusView) {
        defaultView = view
        prefs.edit().putString(KEY_VIEW, view.name).apply()
    }

    /** Whether this box still owes the person the set-up screens ([FirstSetup]): set as a sign-in on the sign-in
     * screen finishes, before the session is adopted, and cleared by the set-up's last key. A box that signed
     * in before the set-up existed never has it set. */
    var setupPending by mutableStateOf(prefs.getBoolean(KEY_SETUP, false))
        private set

    fun beginSetup() {
        setupPending = true
        prefs.edit().putBoolean(KEY_SETUP, true).apply()
    }

    fun endSetup() {
        setupPending = false
        prefs.edit().putBoolean(KEY_SETUP, false).apply()
    }

    /** A box that kept the bare room's view ("Room", the third layout until the screensaver made it redundant) opens
     * on the full view, the nearest to it: the room is still drawn behind the record. Nothing stored, or a name this
     * build does not know, opens on the default. */
    private fun read(): PegasusView =
        when (val name = prefs.getString(KEY_VIEW, null)) {
            "Room" -> PegasusView.Spread
            else -> PegasusView.entries.firstOrNull { it.name == name } ?: PegasusView.Centred
        }

    companion object {
        /** The screensaver's choices in minutes, in the order OK steps through them; 0 is off. */
        val SCREENSAVER_CHOICES = listOf(0, 1, 2, 5, 10, 15, 30)
        private const val SCREENSAVER_DEFAULT = 2
        private const val FILE = SongRadioRuns.SETTINGS_FILE
        private const val KEY_VIEW = "player.view"
        private const val KEY_STATS = "stats.shown"
        private const val KEY_SAVER = "screensaver.minutes"
        private const val KEY_LEVEL = "level.mode"
        private const val KEY_LEVEL_CLASSICAL = "level.classical"
        private const val KEY_RESOLUTION = "screens.resolution"
        private const val KEY_PICTURE = "screens.picture"
        private const val KEY_BACKGROUND = "screens.background"
        private const val KEY_ALBUM_CAROUSEL = "albums.carousel"
        private const val KEY_ALBUM_ORDER = "albums.order"
        private const val KEY_KARAOKE = "lyrics.karaoke"
        private const val KEY_LYRICS = "lyrics.shown"
        private const val KEY_SETUP = "setup.pending"
        private const val KEY_MINI = "mini.place"
        private const val KEY_LEGEND = "screens.legend"
        private const val KEY_ROOM_STYLE = "room.style"
    }
}
