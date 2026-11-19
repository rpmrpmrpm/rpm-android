package com.rpm.player.phone.design

/**
 * The icon glyphs the app draws. The values match the iPhone app's `Glyph` (RPMUI/RPMTheme.swift),
 * codepoint for codepoint. They are written as escapes because they are private-use characters.
 */
object Glyph {
    const val apple = "\uF179"
    const val google = "\uF1A0"
    const val play = "\uF04B"
    const val shuffle = "\uF074"
    const val heart = "\uF004"

    // A broken heart, for the menu item that takes a favourite mark off, where iOS shows `heart.slash`.
    const val unheart = "\uF7A9"
    const val record = "\uF51F"
    const val back = "\uF053"
    const val explicit = "\uF26C"
    const val pause = "\uF04C"

    // Next and previous track, for a bar with no room. The transport keys use the wider pair below.
    const val next = "\uF051"
    const val previous = "\uF048"

    const val forward = "\uF04E"
    const val backward = "\uF04A"

    // Stop. It is a deliberate choice, not a transport key, so only the stage shows it, where there is room.
    const val stop = "\uF04D"

    // Back to the portrait layout, from the stage.
    const val compress = "\uF066"

    // Keeps the stage's controls visible instead of hiding them.
    const val pin = "\uF08D"
    const val more = "\uF141"

    // Three bars: the menu of a playlist page, at the top right where a record has its heart.
    const val menu = "\uF0C9"

    // The marks of a playlist's menu items, standing for the iPhone's `pencil` and `arrow.up.arrow.down`.
    const val pencil = "\uF303"
    const val upDown = "\uF338"
    const val search = "\uF002"
    const val sort = "\uF0DC"

    // The order a list is in. This is not `sort`, which is the caret on a box that opens a menu.
    // The desktop radio pane uses this glyph for the same button.
    const val sortAmount = "\uF160"
    // Font Awesome `tags` icon, for the genres view of the library.
    const val genres = "\uF02C"
    const val refresh = "\uF2F1"

    // A tick, for a row that is already chosen.
    const val check = "\uF00C"

    // The download button on an album page, and its three states.
    const val download = "\uF019"
    const val downloaded = "\uF058"
    const val stuck = "\uF06A"
    const val home = "\uF015"
    const val library = "\uF001"

    // One song. The desktop uses this mark for a track in the quick selection. The same glyph is the icon of the
    // library tab.
    const val song = "\uF001"
    const val playlists = "\uF8C9"

    // A folder, for a row that opens a place rather than a list. It is the only row on the playlists
    // tab that navigates instead of opening something.
    const val folder = "\uF07B"

    // The marks of the computer's mood folders and its decades folder, as the desktop draws them
    // (SmartPlaylistFace.Glyph and SmartPlaylistFace.DecadesFolderGlyph). The iPhone has no marks for these.
    const val calm = "\uF52D"        // a feather
    const val energetic = "\uF0E7"   // a bolt
    const val danceable = "\uF70C"   // a person running
    const val focus = "\uF140"       // a bullseye
    const val sleep = "\uF236"       // a bed
    const val melancholy = "\uF73D"  // a rain cloud
    const val happy = "\uF185"       // the sun
    const val angry = "\uF7E4"       // a curved flame
    const val romantic = "\uF5CE"    // a wine glass
    const val zen = "\uF5BB"         // a lotus
    const val newYearsEve = "\uF79F" // two champagne glasses
    const val christmas = "\uF1BB"   // a Christmas tree
    const val decades = "\uF017"     // a clock: the folder is arranged by when, not by what

    // A chevron, for a row that opens a deeper screen.
    const val into = "\uF054"

    // The two playlist layouts: covers in a grid, or rows in a list. The desktop uses the same pair
    // on the segment control above its tree view.
    const val grid = "\uF00A"
    const val rows = "\uF03A"

    const val plus = "\uF067"
    const val grip = "\uF7A4"
    const val remove = "\uF2ED"

    // The queue. It is not the play mark, because this glyph stands for the queue itself.
    const val queue = "\uF8D9"

    const val cloud = "\uF0C2"

    // A picture, for the card that chooses the background art.
    const val picture = "\uF03E"
    const val speaker = "\uF028"

    // A balance scale, for volume levelling. The desktop's Sound settings use the same glyph.
    const val levelling = "\uF24E"
    const val phone = "\uF3CD"
    const val about = "\uF05A"

    // Icons for the About screen's links: a sheet of paper for a licence whose text is shipped with the
    // app, an arrow out of a box for terms that only the publisher hosts, a chain for a project's own
    // page, and a terminal for the offer of source code.
    const val license = "\uF15C"
    const val outward = "\uF35D"
    const val link = "\uF0C1"
    const val code = "\uF121"
    const val radio = "\uF519"
    // A cassette tape: a mixtape of songs like one song, kept apart from the internet radio's mark.
    const val mixtape = "\uF8AB"
    const val profile = "\uF007"

    /** A cog, for the settings tab. */
    const val settings = "\uF013"

    /** Sliders, for the general section of the settings page. The cog is the tab's own mark. */
    const val general = "\uF1DE"

    /** A speech bubble, for Send feedback. */
    const val feedback = "\uF075"

    /** Thumbs up and down, for likes and dislikes on a post or a reply, and the arrow of Reply. */
    const val like = "\uF164"
    const val dislike = "\uF165"
    const val reply = "\uF3E5"

    /** A speech balloon, for the replies under a post. */
    const val comment = "\uF075"

    /** A newspaper, for the Feed tab. */
    const val feed = "\uF1EA"

    /** The cross that closes a search row. The circled one clears a search box. */
    const val close = "\uF00D"
    const val clear = "\uF057"

    /** Sort icons: an artist's songs by title (A to Z), and by year. */
    const val byLetter = "\uF15D"
    const val byYear = "\uF073"
}
