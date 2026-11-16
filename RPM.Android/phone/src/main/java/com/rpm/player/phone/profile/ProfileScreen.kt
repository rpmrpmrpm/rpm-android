package com.rpm.player.phone.profile

import com.rpm.player.core.library.Cover
import com.rpm.player.phone.library.SleeveSquare
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CommunityClient
import com.rpm.player.core.cloud.FeedItem
import com.rpm.player.core.cloud.FeedReview
import com.rpm.player.core.cloud.Friendship
import com.rpm.player.core.cloud.PersonProfile
import com.rpm.player.core.cloud.Post
import com.rpm.player.core.cloud.Song
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.phone.design.ActionSheet
import com.rpm.player.phone.design.BackKey
import com.rpm.player.phone.design.DialogKey
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.GlassKey
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.KeyRole
import com.rpm.player.phone.design.OverChrome
import com.rpm.player.phone.design.PullDownMenu
import com.rpm.player.phone.design.PullItem
import com.rpm.player.phone.design.Weight
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.kicker
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.rememberPicture
import com.rpm.player.phone.design.system
import com.rpm.player.phone.library.ReportSheet
import com.rpm.player.phone.library.Said
import com.rpm.player.phone.library.StarRating
import com.rpm.player.phone.ui.Ground
import com.rpm.player.phone.ui.LocalGround
import com.rpm.player.phone.ui.VinylGround
import com.rpm.player.core.cloud.PageGround
import com.rpm.player.core.cloud.ProfileBadges
import androidx.compose.runtime.CompositionLocalProvider
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The banner's height, under the status bar too. */
private val BANNER_TALL = 190.dp
private val PHOTO = 120.dp

/** How far the photo's centre stands above the banner's foot. */
private val PHOTO_LIFT = 14.dp
private val MARGIN = 20.dp

/** A review's cover, at the right of its card; the review's text runs beside it and on under it. */
internal val REVIEW_COVER = 120.dp

/** The most lines of a review a card shows; the whole review is a tap away. */
internal const val REVIEW_LINES = 10

/**
 * A person's page: their banner and photo, name, collection counts, what they are playing, and their posts and reviews,
 * newest first. It is the iPhone's `ProfilePage` with `PersonScreen`: the same screen is the profile tab ([PersonPage.isOwn])
 * and anyone else's page, opened from a name.
 *
 * Every entry in the feed is the page owner's own, so every row uses the owner's name and photo.
 *
 * @param playing the song playing on this phone, for the post box's switch; null when nothing is.
 * @param albumOf the reader's own copy of a reviewed album, for Open album; null when it is not in their collection.
 * @param onBack null on the profile tab, which has no back key.
 */
@Composable
fun ProfileScreen(
    page: PersonPage,
    notices: Notices?,
    images: ImageLoader,
    me: String?,
    bottomRoom: Dp,
    playing: Song?,
    albumOf: (FeedReview) -> CloudAlbum?,
    onBack: (() -> Unit)?,
    onOpenPerson: (String) -> Unit,
    onOpenAlbum: (CloudAlbum) -> Unit,
    /** Whether the reader wears the supporter's mark, from their own plan: the reader's own profile does not say. */
    ownWearsMark: Boolean = false,
    /** Finds a photo for the sample page's banner: the playing artist's, as the artist page shows it. */
    artistPhoto: (suspend (artist: String, album: String) -> String?)? = null,
    /** The badges the reader's own collection unlocks, worked out on this phone ([ProfileBadges.unlocked]). */
    ownBadges: List<String> = emptyList(),
    /** The cover of a record in the reader's collection, for the reviews. */
    coverOf: (CloudAlbum) -> Cover? = { null },
    /** The cover of a record the reader does not have, by artist and title, for the reviews. */
    albumCover: (suspend (artist: String, title: String) -> String?)? = null,
) {
    val scope = rememberCoroutineScope()
    val backdrop = rememberBackdrop()
    val context = LocalContext.current
    var menuAt by remember { mutableStateOf<Rect?>(null) }
    var postMenu by remember { mutableStateOf<Pair<Post, Rect>?>(null) }
    var reporting by remember { mutableStateOf<Post?>(null) }
    var confirmingBlock by remember { mutableStateOf(false) }
    var showingNotices by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // The review open in full, by its key in the feed, so its votes stay current.
    var readingReview by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(page.userId) {
        page.read()
        notices?.readUnread()
        page.pendingPost?.let { post ->
            page.pendingPost = null
            page.open(post)
        }
    }
    // Back goes to the page before, on someone else's page. The profile tab has no back.
    BackHandler(enabled = onBack != null) { onBack?.invoke() }

    // The banner is the playing artist's photo when the owner chose that, and on the sample page unless a photo was
    // chosen there. The edit page shows the same photo in its banner card.
    val listening = page.profile?.listening
    val follows = page.profile?.bannerFollowsListening == true || (page.isSample && page.profile?.bannerSource != "photo")
    val playingPhoto by produceState<String?>(null, listening?.artist) {
        value = if (listening != null) artistPhoto?.invoke(listening.artist, listening.album) else null
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            PageGroundView(page.profile?.pageGround.orEmpty())
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "top", contentType = "top") {
                    // The sample page shows the badge, so the look can be seen; a real page shows it to supporters.
                    val supporter = page.profile?.supporter == true || (page.isOwn && ownWearsMark) || page.isSample
                    Top(page, images, supporter, if (follows) playingPhoto else page.banner, onFriend = { step -> scope.launch { page.friend(step) } })
                }
                if (page.isOwn) {
                    item(key = "box", contentType = "box") {
                        PostBox(page, playing, Modifier.padding(horizontal = MARGIN).padding(top = 16.dp)) {
                            scope.launch { page.post(playing) }
                        }
                    }
                }
                if (page.isSample) {
                    item(key = "sample") {
                        BasicText(
                            stringResource(R.string.Rpm_Profile_SampleNote),
                            Modifier.padding(horizontal = MARGIN).padding(top = 16.dp),
                            style = kicker(),
                        )
                    }
                }
                val profile = page.profile
                val trouble = page.trouble
                when {
                    profile?.blocked == true -> item(key = "blocked") {
                        Column(Modifier.padding(horizontal = MARGIN).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Quiet(stringResource(R.string.Rpm_Profile_Blocked))
                            SmallKey(stringResource(R.string.Rpm_Profile_Unblock), Weight.Ghost) { scope.launch { page.unblock() } }
                        }
                    }
                    trouble != null -> item(key = "trouble") {
                        Quiet(stringResource(trouble), Modifier.padding(horizontal = MARGIN).padding(top = 16.dp))
                    }
                    page.answered && page.feed.isEmpty() -> item(key = "empty") {
                        Quiet(
                            stringResource(if (page.isOwn) R.string.Rpm_Profile_FeedEmptyOwn else R.string.Rpm_Profile_FeedEmpty),
                            Modifier.padding(horizontal = MARGIN).padding(top = 16.dp),
                        )
                    }
                    else -> {
                        items(page.feed, key = { it.key }, contentType = { if (it is FeedItem.OfPost) "post" else "review" }) { item ->
                            val modifier = Modifier.padding(horizontal = MARGIN).padding(top = 12.dp)
                            when (item) {
                                is FeedItem.OfPost -> PostRow(
                                    item.post, page.photo, images, isOwn = page.isOwn,
                                    supporter = item.post.authorSupporter || page.profile?.supporter == true || (page.isOwn && ownWearsMark) || page.isSample,
                                    modifier,
                                    onVote = { value -> scope.launch { page.vote(item.post, value) } },
                                    onOpen = { scope.launch { page.open(item.post) } },
                                    onMenu = { at -> postMenu = item.post to at },
                                )
                                is FeedItem.OfReview -> ReviewRow(
                                    item.review, isOwn = page.isOwn, albumOf(item.review), coverOf, albumCover, images, modifier,
                                    onVote = { value -> scope.launch { page.voteReview(item.review.review, value) } },
                                    onRead = { readingReview = item.key },
                                )
                            }
                        }
                        if (page.next != null) {
                            item(key = "more") {
                                LaunchedEffect(page.next) { page.more() }
                                Spacer(Modifier.height(40.dp))
                            }
                        }
                    }
                }
                item(key = "foot") { Spacer(Modifier.height(40.dp + bottomRoom)) }
            }
        }

        // The round keys over the banner. Outside the backdrop's source: glass inside it would frost itself.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = MARGIN, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { BackKey(backdrop, it) }
            Spacer(Modifier.weight(1f))
            val label = stringResource(R.string.Rpm_Profile_Menu)
            MenuKey(backdrop, unread = if (page.isOwn) notices?.unread ?: 0 else 0, own = page.isOwn, label) { menuAt = it }
        }

        menuAt?.let { at ->
            val items = menuItems(page, onNotices = { showingNotices = true }, onEdit = { editing = true },
                onFriend = { step -> scope.launch { page.friend(step) } }, onBlock = { confirmingBlock = true },
                onUnblock = { scope.launch { page.unblock() } })
            OverChrome { PullDownMenu(backdrop, at, items, onDismiss = { menuAt = null }) }
        }
        postMenu?.let { (post, at) ->
            val item = if (page.isOwn) {
                PullItem(stringResource(R.string.Rpm_Profile_DeletePost), destructive = true) { scope.launch { page.delete(post) } }
            } else {
                PullItem(stringResource(R.string.Artists_WallReport)) { reporting = post }
            }
            OverChrome { PullDownMenu(backdrop, at, listOf(item), onDismiss = { postMenu = null }) }
        }
        reporting?.let { post ->
            OverChrome {
                ReportSheet(
                    stringResource(R.string.Album_ReportCommentPrompt),
                    backdrop,
                    bottomRoom,
                    onSend = { note -> page.report(post, note)?.let { Said(it) } },
                    onClose = { reporting = null },
                )
            }
        }
        if (confirmingBlock) {
            OverChrome {
                ActionSheet(
                    backdrop,
                    stringResource(R.string.Rpm_Profile_BlockConfirm, page.profile?.displayName.orEmpty()),
                    null,
                    listOf(DialogKey(stringResource(R.string.Rpm_Profile_Block), KeyRole.Destructive) {
                        confirmingBlock = false
                        scope.launch { page.block() }
                    }),
                    DialogKey(stringResource(R.string.Common_Cancel), KeyRole.Cancel) { confirmingBlock = false },
                )
            }
        }
        page.editTrouble?.let { said ->
            if (!editing) {
                OverChrome {
                    ActionSheet(backdrop, stringResource(said), null, emptyList(),
                        DialogKey(stringResource(R.string.Common_Cancel), KeyRole.Cancel) { page.editTrouble = null })
                }
            }
        }

        // The sheets that rise over the page and the tab bar, as the iPhone's sheets do.
        OverChrome {
            AnimatedVisibility(page.openPost != null, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) {
                page.openPost?.let { post ->
                    ThreadPage(page, post, images, me, bottomRoom,
                        ownerSupporter = post.authorSupporter || page.profile?.supporter == true || (page.isOwn && ownWearsMark) || page.isSample,
                        onOpenPerson = { userId ->
                        if (userId != page.userId) {
                            page.closeThread()
                            onOpenPerson(userId)
                        }
                    })
                }
            }
            if (notices != null) {
                AnimatedVisibility(showingNotices, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) {
                    NoticesPage(notices, images, onClose = { showingNotices = false }, onOpen = { note ->
                        showingNotices = false
                        when (note.kind) {
                            com.rpm.player.core.cloud.Notice.REPLY, com.rpm.player.core.cloud.Notice.VOTE -> {
                                val found = page.feed.firstNotNullOfOrNull { (it as? FeedItem.OfPost)?.post?.takeIf { p -> p.postId == note.postId } }
                                if (found != null) scope.launch { page.open(found) }
                            }
                            else -> if (note.fromUserId.isNotEmpty()) onOpenPerson(note.fromUserId)
                        }
                    })
                }
            }
            // The review read in full. It is looked up in the feed each time, so a vote on it shows at once.
            val reading = readingReview?.let { key -> page.feed.firstOrNull { it.key == key } as? FeedItem.OfReview }
            AnimatedVisibility(reading != null, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) {
                val shown = remember { mutableStateOf<FeedReview?>(null) }
                reading?.let { shown.value = it.review }
                shown.value?.let { review ->
                    ReviewPage(
                        review,
                        isOwn = page.isOwn,
                        album = albumOf(review),
                        coverOf = coverOf,
                        albumCover = albumCover,
                        images = images,
                        ground = page.profile?.pageGround.orEmpty(),
                        onVote = { value -> scope.launch { page.voteReview(review.review, value) } },
                        onOpenAlbum = { album -> readingReview = null; onOpenAlbum(album) },
                        onClose = { readingReview = null },
                    )
                }
            }
            AnimatedVisibility(editing, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) {
                EditPage(
                    page,
                    images,
                    ownBadges = ownBadges,
                    playingPhoto = playingPhoto,
                    onClose = { editing = false },
                )
            }
        }
    }
}

internal enum class Picked { Photo, Banner }

/**
 * What a profile page is drawn on ([PersonProfile.pageGround]): a solid colour, or one of the app's backgrounds drawn
 * as every page draws its own. An empty or unknown value is the app's standard background, not the reader's choice:
 * a person's page looks the same to everyone.
 */
@Composable
internal fun PageGroundView(wire: String) {
    when (val ground = PageGround.of(wire)) {
        is PageGround.Colour -> Box(Modifier.fillMaxSize().background(Color(ground.argb)))
        is PageGround.Backdrop -> CompositionLocalProvider(LocalGround provides (Ground.entries.firstOrNull { it.id == ground.id } ?: Ground.Standard)) {
            VinylGround()
        }
        PageGround.Standard -> CompositionLocalProvider(LocalGround provides Ground.Standard) { VinylGround() }
    }
}

/** The menu of the round key at the top right. */
@Composable
private fun menuItems(
    page: PersonPage,
    onNotices: () -> Unit,
    onEdit: () -> Unit,
    onFriend: (FriendStep) -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
): List<PullItem> {
    val profile = page.profile
    val items = ArrayList<PullItem>()
    if (page.isOwn) {
        items += PullItem(stringResource(R.string.Rpm_Notify_Title), onPress = onNotices)
        items += PullItem(stringResource(R.string.Rpm_Profile_EditPage), onPress = onEdit)
    } else if (profile != null) {
        when (profile.friendship) {
            Friendship.Requested -> items += PullItem(stringResource(R.string.Rpm_Profile_CancelRequest)) { onFriend(FriendStep.Cancel) }
            Friendship.Friends -> items += PullItem(stringResource(R.string.Rpm_Profile_RemoveFriend), destructive = true) { onFriend(FriendStep.Remove) }
            else -> Unit
        }
        items += if (profile.blocked) {
            PullItem(stringResource(R.string.Rpm_Profile_Unblock), onPress = onUnblock)
        } else {
            PullItem(stringResource(R.string.Rpm_Profile_Block), destructive = true, onPress = onBlock)
        }
    }
    return items
}

/** The round menu key, with the unread count on it on the reader's own page. */
@Composable
private fun MenuKey(backdrop: com.rpm.player.phone.design.Backdrop, unread: Int, own: Boolean, label: String, onOpen: (Rect) -> Unit) {
    var at by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        GlassKey(backdrop, { onOpen(at) }, Modifier.semantics { contentDescription = label }) {
            BasicText(if (own) Glyph.menu else Glyph.more, style = icon(16f).copy(color = Ink.white(0xE6)))
        }
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .defaultMinSize(18.dp, 18.dp)
                    .clip(CircleShape)
                    .background(Ink.red)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(if (unread > 99) "99+" else unread.toString(), style = system(11f, FontWeight.Bold).copy(color = Color.White))
            }
        }
    }
}

// ── the top: banner, photo, name and the lines under it ──

@Composable
private fun Top(page: PersonPage, images: ImageLoader, supporter: Boolean, banner: String?, onFriend: (FriendStep) -> Unit) {
    val profile = page.profile
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Drawn after the lines under it (zIndex), so a supporter's glow can drift over the name.
        Box(Modifier.fillMaxWidth().height(BANNER_TALL + PHOTO / 2 - PHOTO_LIFT).zIndex(1f)) {
            banner?.let { address -> FadingBanner(address, images) }
            // A supporter's photo has a gold ring and a glow that lights up around it.
            Box(Modifier.align(Alignment.BottomCenter), contentAlignment = Alignment.Center) {
                Box(Modifier.border(if (supporter) 3.dp else 2.dp, if (supporter) Ink.gold else Ink.white(0x33), CircleShape)) {
                    PersonPicture(page.photo ?: profile?.let { LocalSampleFaces.current[it.userId] }, profile?.displayName.orEmpty(), PHOTO, images)
                }
                if (supporter) SupporterSparkles(PHOTO)
            }
        }
        if (profile != null) {
            // The name sits close under the photo.
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = MARGIN), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // A supporter's name is in gold, beside the gold badge.
                BasicText(profile.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(22f).copy(color = if (supporter) Ink.gold else Color.White))
                // The supporter's heart alone, in gold, as the badge in the settings carries it.
                if (supporter) BasicText(Glyph.heart, style = icon(16f).copy(color = Ink.gold))
            }
            profile.badge.takeIf { it.isNotEmpty() }?.let(ProfileBadges::byId)?.let { badge ->
                Spacer(Modifier.height(6.dp))
                BadgePill(badge, profile.badgeColour)
            }
            // The counts and the date as one block of two lines, close together.
            Spacer(Modifier.height(6.dp))
            counts(profile)?.let { BasicText(it, style = kicker()) }
            if (profile.createdAt > 0) {
                BasicText(stringResource(R.string.Rpm_Profile_MemberSince, since(profile.createdAt)), style = display(12f).copy(color = Ink.white(0x88)))
            }
            profile.listening?.let { now ->
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .padding(horizontal = MARGIN)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(Glyph.play, style = icon(11f).copy(color = Color.White))
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        stringResource(R.string.Rpm_Profile_NowPlaying, now.title, now.artist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = display(14f).copy(color = Ink.white(0xEE)),
                    )
                }
            }
            if (!page.isOwn) {
                Spacer(Modifier.height(12.dp))
                FriendKey(profile.friendship, onFriend)
            }
        }
    }
}

/**
 * The banner across the top, as the artist page's photograph: sharp at the top, blurred towards its foot, and faded
 * out into the page's own background, so it has no lower edge. The blur needs Android 12; before that the banner only
 * fades.
 */
@Composable
private fun FadingBanner(address: String, images: ImageLoader) {
    val picture = rememberPicture(address, 400.dp, BANNER_TALL + PHOTO / 2)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Keeps the top whole and lets the page show through towards the foot.
                drawRect(Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    ) {
        AsyncImage(model = picture, contentDescription = null, imageLoader = images, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // The same picture blurred, taking over from the middle down.
        AsyncImage(
            model = picture,
            contentDescription = null,
            imageLoader = images,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(24.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0.3f to Color.Transparent, 0.75f to Color.Black), blendMode = BlendMode.DstIn)
                },
        )
    }
}

/**
 * A badge as the page shows it under the name: its name in italics, stamped on a pill the way the supporter's mark is
 * ([com.rpm.player.phone.settings.SupporterMark]), in the badge's colour and not in gold. The face runs diagonally from
 * a dark corner through a light band; the rim is a step brighter; the lettering is the lightest tint of the colour.
 */
@Composable
internal fun BadgePill(badge: ProfileBadges.Badge, colour: String, modifier: Modifier = Modifier, size: Float = 14f) {
    val base = badgeColour(colour)
    val dark = lerp(base, Color.Black, 0.35f)
    val light = lerp(base, Color.White, 0.55f)
    BasicText(
        stringResource(badge.name),
        modifier
            .drawBehind {
                val corner = CornerRadius(size.dp.toPx())
                val start = Offset(0f, this.size.height)
                val end = Offset(this.size.width, 0f)
                drawRoundRect(
                    Brush.linearGradient(
                        0f to dark.copy(alpha = 0.55f),
                        0.30f to base.copy(alpha = 0.60f),
                        0.42f to light.copy(alpha = 0.70f),
                        0.52f to base.copy(alpha = 0.60f),
                        1f to dark.copy(alpha = 0.55f),
                        start = start,
                        end = end,
                    ),
                    cornerRadius = corner,
                )
                val edge = 1.dp.toPx()
                drawRoundRect(
                    Brush.linearGradient(
                        0f to base.copy(alpha = 0.70f),
                        0.45f to light,
                        1f to base.copy(alpha = 0.70f),
                        start = start,
                        end = end,
                    ),
                    topLeft = Offset(edge / 2, edge / 2),
                    size = this.size.copy(width = this.size.width - edge, height = this.size.height - edge),
                    cornerRadius = CornerRadius(corner.x - edge / 2),
                    style = Stroke(edge),
                )
            }
            .padding(horizontal = 14.dp, vertical = 4.dp),
        maxLines = 1,
        style = display(size).copy(color = lerp(base, Color.White, 0.8f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
    )
}

/** A badge's colour from its wire value, `#RRGGBB`; the first of [ProfileBadges.colours] when it has none. */
internal fun badgeColour(wire: String): Color {
    val value = wire.removePrefix("#").toLongOrNull(16)?.takeIf { wire.length == 7 } ?: ProfileBadges.colours.first()
    return Color(0xFF000000 or value)
}

@Composable
private fun counts(profile: PersonProfile): String? {
    val counts = profile.counts ?: return null
    if (counts.albums <= 0) return null
    // Grouped as the phone's language groups thousands: "1,610".
    val number = java.text.NumberFormat.getIntegerInstance()
    val records = if (counts.albums == 1) stringResource(R.string.Stats_AlbumsOne) else stringResource(R.string.Stats_Albums, number.format(counts.albums))
    val tracks = if (counts.tracks == 1) stringResource(R.string.Rpm_Profile_TracksOne) else stringResource(R.string.Rpm_Queue_Count, number.format(counts.tracks))
    return stringResource(R.string.Rpm_Library_CountsJoin, records, tracks)
}

/** A month and a year: "November 2026". */
private fun since(millis: Long): String =
    DateTimeFormatter.ofPattern("LLLL yyyy").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** The friendship key under someone else's name. What it says follows the state; the menu has the other actions. */
@Composable
private fun FriendKey(friendship: Friendship, onFriend: (FriendStep) -> Unit) {
    when (friendship) {
        Friendship.None -> SmallKey(stringResource(R.string.Rpm_Profile_AddFriend), Weight.Primary) { onFriend(FriendStep.Add) }
        Friendship.Requested -> SmallKey(stringResource(R.string.Rpm_Profile_Requested), Weight.Ghost) { onFriend(FriendStep.Cancel) }
        Friendship.Incoming -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallKey(stringResource(R.string.Rpm_Profile_Accept), Weight.Primary) { onFriend(FriendStep.Accept) }
            SmallKey(stringResource(R.string.Rpm_Profile_Decline), Weight.Ghost) { onFriend(FriendStep.Decline) }
        }
        Friendship.Friends -> SmallKey(stringResource(R.string.Rpm_Profile_Friends), Weight.Ghost, enabled = false) {}
        Friendship.Own -> Unit
    }
}

@Composable
internal fun SmallKey(title: String, weight: Weight, enabled: Boolean = true, onClick: () -> Unit) {
    GlassButton(onClick = onClick, weight = weight, enabled = enabled, stretches = false, height = 34.dp, radius = 16.dp, inset = 18.dp, size = 13f) { style ->
        BasicText(title, maxLines = 1, style = style)
    }
}

/** A person's photo in a circle, or the first letter of their name when they have none or it has not loaded. */
@Composable
internal fun PersonPicture(address: String?, name: String, size: Dp, images: ImageLoader) {
    Box(Modifier.size(size).clip(CircleShape).background(Ink.white(0x26)), contentAlignment = Alignment.Center) {
        val first = name.firstOrNull()
        BasicText(
            if (first != null && first.isLetterOrDigit()) first.uppercase() else "?",
            style = system(size.value * 0.42f, FontWeight.SemiBold).copy(color = Ink.white(0xDD)),
        )
        if (address != null) {
            AsyncImage(
                model = rememberPicture(address, size),
                contentDescription = null,
                imageLoader = images,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun Quiet(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = display(13f).copy(color = Ink.white(0x88)))
}

/** "Title by Artist", for a post's song and the post box's switch, as the bar under the name says it. */
@Composable
internal fun songLine(song: Song): String =
    if (song.artist.isEmpty()) song.title else stringResource(R.string.Rpm_Profile_NowPlaying, song.title, song.artist)

/** When something was written, in words a person would use. */
internal fun written(millis: Long): String =
    if (millis <= 0) "" else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

// ── the post box, on the own page ──

@Composable
private fun PostBox(page: PersonPage, playing: Song?, modifier: Modifier, onSend: () -> Unit) {
    val card = RoundedCornerShape(14.dp)
    val canSend = !page.sending && page.draft.isNotBlank()
    Column(
        modifier
            .fillMaxWidth()
            .clip(card)
            .background(Ink.white(0x14))
            .border(1.dp, Ink.white(0x22), card)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                page.draft,
                onValueChange = { page.draft = it.take(CommunityClient.LONGEST_WALL_POST) },
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                textStyle = display(15f).copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                minLines = 1,
                maxLines = 6,
                decorationBox = { field ->
                    Box {
                        if (page.draft.isEmpty()) BasicText(stringResource(R.string.Rpm_Profile_PostPrompt), style = display(15f).copy(color = Ink.white(0x66)))
                        field()
                    }
                },
            )
            GlassButton(onClick = onSend, weight = Weight.Primary, enabled = canSend, stretches = false, height = 32.dp, radius = 15.dp, inset = 14.dp, size = 13f) { style ->
                BasicText(stringResource(R.string.Rpm_Profile_Post), style = style)
            }
        }
        // Only while something plays: there is nothing to add otherwise.
        if (playing != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { page.withSong = !page.withSong },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(stringResource(R.string.Rpm_Profile_WithSong), style = display(12f).copy(color = Ink.white(0xB3)))
                    BasicText(songLine(playing), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(11f).copy(color = Ink.white(0x77)))
                }
                Spacer(Modifier.width(10.dp))
                com.rpm.player.phone.settings.Switch(page.withSong)
            }
        }
    }
}

// ── the feed ──

@Composable
internal fun PostRow(
    post: Post,
    photo: String?,
    images: ImageLoader,
    isOwn: Boolean,
    supporter: Boolean,
    modifier: Modifier,
    onVote: (Int) -> Unit,
    onOpen: () -> Unit,
    /** Opens the corner menu; null draws no corner key, as on the Feed tab. */
    onMenu: ((Rect) -> Unit)?,
    /** Opens the author's page from the name or the photo; null on their own page. */
    onPerson: (() -> Unit)? = null,
) {
    val name = if (post.authorDeleted) stringResource(R.string.Community_DeletedUser) else post.authorName
    var corner by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.white(0x12))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
    ) {
        // The vote keys are 32 dp tall with their marks in the middle, so the foot needs little room of its own.
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, enabled = onPerson != null) { onPerson?.invoke() }) {
                PersonPicture(photo, name, 40.dp, images)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .padding(end = if (onMenu != null) 24.dp else 0.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, enabled = onPerson != null) { onPerson?.invoke() },
                ) {
                    Byline(name, post.createdAt, gold = supporter)
                }
                BasicText(post.body, style = display(14f).copy(color = Ink.white(0xE6)))
                post.song?.let { song ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(Glyph.play, style = icon(9f).copy(color = Ink.white(0xAA)))
                        BasicText(songLine(song), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(12f).copy(color = Ink.white(0xAA)))
                    }
                }
                // The thumbs start where the text starts; the replies end where the card's corner key ends.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    VoteKeys(post.ups, post.downs, post.myVote, enabled = !isOwn, onVote = onVote)
                    Spacer(Modifier.weight(1f))
                    ReplyKey(post.replyCount, onOpen)
                }
            }
        }
        if (onMenu != null) {
            // In the corner, level with the name; the dots end at the card's inner edge, as the replies do.
            val more = stringResource(R.string.Common_MoreOptions)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 3.dp)
                    .size(34.dp, 28.dp)
                    .onGloballyPositioned { corner = it.boundsInRoot() }
                    .semantics { contentDescription = more }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onMenu(corner) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(Glyph.more, style = icon(13f).copy(color = Ink.white(0xAA)))
            }
        }
    }
}

/** A name and when it wrote. A supporter's name is in gold ([gold]). */
@Composable
internal fun Byline(name: String, millis: Long, gold: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(14f, FontWeight.SemiBold).copy(color = if (gold) Ink.gold else Color.White))
        BasicText(written(millis), maxLines = 1, style = display(11f).copy(color = Ink.white(0x77)))
    }
}

/**
 * Thumbs up with the likes beside it, thumbs down with the dislikes: on a post, a review and a reply. The reader's own
 * vote is lit, and a tap on it takes the vote back. On the reader's own post or reply the counts are shown and cannot
 * be pressed ([enabled]): nobody votes on their own.
 */
@Composable
internal fun VoteKeys(ups: Int, downs: Int, mine: Int, enabled: Boolean, onVote: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VoteKey(Glyph.like, ups, 1, mine, enabled, stringResource(R.string.Rpm_Profile_Likes, ups), onVote, first = true)
        VoteKey(Glyph.dislike, downs, -1, mine, enabled, stringResource(R.string.Rpm_Profile_Dislikes, downs), onVote)
    }
}

@Composable
private fun VoteKey(glyph: String, count: Int, value: Int, mine: Int, enabled: Boolean, label: String, onVote: (Int) -> Unit, first: Boolean = false) {
    val lit = mine == value
    Row(
        Modifier
            .height(32.dp)
            .semantics { contentDescription = label }
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button) {
                onVote(if (lit) 0 else value)
            }
            .padding(start = if (first) 0.dp else 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(glyph, style = icon(13f).copy(color = Ink.white(if (lit) 0xFF else 0x99)))
        BasicText(count.toString(), style = display(12f).copy(color = Ink.white(if (lit) 0xEE else 0x99)))
    }
}

/** The reply arrow with how many replies there are, or the word Reply when there are none. Opens the thread. */
@Composable
internal fun ReplyKey(count: Int, onClick: () -> Unit) {
    val label = when (count) {
        0 -> stringResource(R.string.Rpm_Profile_Reply)
        1 -> stringResource(R.string.Rpm_Profile_RepliesOne)
        else -> stringResource(R.string.Rpm_Profile_Replies, count)
    }
    Row(
        Modifier
            .height(32.dp)
            .semantics { contentDescription = label }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(Glyph.comment, style = icon(13f).copy(color = Ink.white(0x99)))
        BasicText(if (count == 0) stringResource(R.string.Rpm_Profile_Reply) else count.toString(), style = display(12f).copy(color = Ink.white(0xCC)))
    }
}

/** Makes a picked photo small enough for the server: JPEG, cut to the shape it is shown in. It is the iPhone's `PicturePrep`. */
internal object PicturePrep {
    fun prepare(context: Context, uri: Uri, target: Picked): ByteArray? = when (target) {
        // A square of 512 px, at most 500 KB (the server takes 512 KB).
        Picked.Photo -> jpeg(context, uri, 512, 512, 500_000)
        // 1500 by 500, at most 1.9 MB (the server takes 2 MB).
        Picked.Banner -> jpeg(context, uri, 1500, 500, 1_900_000)
    }

    /** Draws the picture to fill [wide] by [high], cut at the centre, and lowers the JPEG quality until it fits. */
    private fun jpeg(context: Context, uri: Uri, wide: Int, high: Int, limit: Int): ByteArray? = runCatching {
        // ImageDecoder turns the photo upright from its EXIF orientation; a software bitmap can be drawn on a Canvas.
        val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // Never larger than the photo itself.
        val fill = maxOf(wide.toFloat() / source.width, high.toFloat() / source.height)
        val outWide = if (fill > 1) (wide / fill).toInt().coerceAtLeast(1) else wide
        val outHigh = if (fill > 1) (high / fill).toInt().coerceAtLeast(1) else high
        val scale = maxOf(outWide.toFloat() / source.width, outHigh.toFloat() / source.height)
        val cropWide = (outWide / scale).toInt()
        val cropHigh = (outHigh / scale).toInt()
        val left = (source.width - cropWide) / 2
        val top = (source.height - cropHigh) / 2
        val out = Bitmap.createBitmap(outWide, outHigh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, AndroidRect(left, top, left + cropWide, top + cropHigh), AndroidRect(0, 0, outWide, outHigh), null)
        for (quality in intArrayOf(85, 70, 55, 40)) {
            val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size <= limit) return bytes
        }
        null
    }.getOrNull()
}
