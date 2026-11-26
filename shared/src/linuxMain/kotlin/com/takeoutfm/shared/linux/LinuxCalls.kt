package com.takeoutfm.shared.linux

import com.takeoutfm.shared.offline.CacheLimit
import com.takeoutfm.shared.api.DeviceCode
import com.takeoutfm.shared.api.TakeoutApi
import com.takeoutfm.shared.listen.ListenSession
import com.takeoutfm.shared.music.ActivityInterval
import com.takeoutfm.shared.music.ArtistMix
import com.takeoutfm.shared.music.LiveRadio
import com.takeoutfm.shared.music.LiveSlot
import com.takeoutfm.shared.music.LiveStart
import com.takeoutfm.shared.music.MusicQueue
import com.takeoutfm.shared.music.QueueItem
import com.takeoutfm.shared.music.StreamSong
import com.takeoutfm.shared.listen.parseStreamTitle
import com.takeoutfm.shared.music.Track
import com.takeoutfm.shared.podcast.EpisodeProgress
import com.takeoutfm.shared.podcast.PodcastEpisode
import com.takeoutfm.shared.podcast.TakeoutPodcastRepository
import com.takeoutfm.shared.progress.ProgressRules
import com.takeoutfm.shared.settings.ThemeMode
import com.takeoutfm.shared.guide.GuideLoader
import com.takeoutfm.shared.guide.page
import com.takeoutfm.shared.guide.tuneLive
import kotlinx.coroutines.Job
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** The JSON of the bridge: every field written (C++ reads them all), unknown ones ignored. */
internal val bridgeJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** A call's arguments, the JSON object the app passed. */
internal class Args(private val o: JsonObject) {
    inline fun <reified T> get(name: String): T =
        bridgeJson.decodeFromJsonElement(field(name) ?: throw IllegalArgumentException("missing argument $name"))

    inline fun <reified T> get(name: String, default: T): T =
        field(name)?.let { bridgeJson.decodeFromJsonElement<T>(it) } ?: default

    fun field(name: String): JsonElement? = o[name]?.takeIf { it != JsonNull }
}

@Serializable
data class SessionInfo(val signedIn: Boolean, val endpoint: String, val user: String? = null)

/**
 * A song from Stream History for the Recently Played page: the stream's title split into
 * [artist] and [song] where it reads "Artist - Title" (else [song] is the whole title).
 */
@Serializable
data class HeardSong(
    val station: String,
    val imageUri: String,
    val artist: String?,
    val song: String,
    /** Epoch ms. */
    val heardAt: Long,
) {
    constructor(heard: StreamSong) : this(
        heard.station,
        heard.imageUri,
        parseStreamTitle(heard.title)?.first,
        parseStreamTitle(heard.title)?.second ?: heard.title,
        heard.heardAt,
    )
}

@Serializable
/** A sign-in code, the page to approve it on, and the seconds it's valid for from now. */
data class LinkCode(val code: String, val link: String, val expiresIn: Int)

@Serializable
data class Settings(
    val reportPlays: Boolean,
    val submitListens: Boolean,
    val playbackCaching: Boolean,
    val theme: ThemeMode,
    /** Repeat All kept for every queue played (Repeat One isn't kept). */
    val repeatAll: Boolean,
)

/**
 * What the Linux app can ask of the shared core, by name: the Mac's SwiftCalls, plus device-code
 * sign-in and the listen session (fed by the C++ player). Each call takes a JSON object of
 * arguments and answers with JSON of the shared types (null for calls with nothing to return).
 */
class LinuxCalls(private val core: LinuxCore) {
    private val calls = HashMap<String, suspend (Args) -> JsonElement>()

    private inline fun <reified T> call(name: String, noinline body: suspend (Args) -> T) {
        calls[name] = { args -> bridgeJson.encodeToJsonElement(body(args)) }
    }

    private fun action(name: String, body: suspend (Args) -> Unit) {
        calls[name] = { args -> body(args); JsonNull }
    }

    /** Null when there's no call [name]. */
    suspend fun invoke(name: String, args: JsonObject): JsonElement? = calls[name]?.invoke(Args(args))

    /** The flows the app can follow with `takeout_watch`. */
    fun watchable(name: String): Flow<JsonElement>? = when (name) {
        "session" -> core.session.state.map { SessionInfo(it.isLoggedIn, it.endpoint, it.user) }.encoded()
        "library" -> core.library.index.encoded()
        "favoriteTracks" -> core.music.favoriteTrackEtags.encoded()
        // Decision 017: pages follow these instead of loading again after a change.
        "podcastSubscriptions" -> core.podcasts.subscribedSeries.encoded()
        "playlists" -> core.music.currentPlaylists.encoded()
        // Each started or played episode's progress by GUID ("12 min left", Played), following
        // playback and Mark as Played; one missing hasn't been started.
        "episodeProgress" -> core.progress.offsets.map { offsets ->
            offsets.mapValues { TakeoutPodcastRepository.episodeProgress(it.value) }
                .filterValues { it != EpisodeProgress.NotStarted }
        }.distinctUntilChanged().encoded()
        "recentlyPlayed" -> core.history.entries.encoded()
        // Songs heard on live streams, newest first (RADIO-5).
        "streamHistory" -> core.streamHistory.songs.map { songs -> songs.map(::HeardSong) }.encoded()
        // The favorite artists' ids, following ♥ on an artist (LIB-6).
        "favoriteArtists" -> core.music.favoriteArtistIds.encoded()
        "listenBrainz" -> core.listenBrainz.user.encoded()
        "downloads" -> core.downloads.downloads.map { it.values.toList() }.encoded()
        "cacheUsage" -> core.cachePurge.usage.encoded()
        "settings" -> combine(
            core.settings.reportPlays, core.settings.submitListens,
            core.settings.playbackCaching, core.settings.theme, core.settings.repeatAll, ::Settings,
        ).encoded()
        else -> null
    }

    private inline fun <reified T> Flow<T>.encoded(): Flow<JsonElement> = map { bridgeJson.encodeToJsonElement(it) }

    // Device-code sign-in: requestCode, then awaitCode until the user approves at <server>/link.
    private var pending: DeviceCode? = null
    private var awaiting: Job? = null

    // ListenSession isn't thread-safe; calls run on any thread.
    private val listens = ListenSession(core.listenReporter)
    private val listensLock = Mutex()

    private suspend fun listen(block: ListenSession.() -> Unit) = listensLock.withLock { listens.block() }

    init {
        call("version") { BridgeVersion }

        // ---- Session ----
        call("requestCode") { a ->
            awaiting?.cancel() // a new code replaces the one being waited for
            val code = core.api.requestCode(TakeoutApi.normalizeEndpoint(a.get<String>("host")))
            pending = code
            LinkCode(code.code, code.link, code.expiresIn)
        }
        /** True once signed in; false when the code expired (ask for a new one). */
        call("awaitCode") {
            val code = pending ?: throw IllegalStateException("no code requested")
            awaiting = currentCoroutineContext()[Job]
            val tokens = core.api.awaitCode(code) ?: return@call false
            pending = null
            core.session.signIn(code.endpoint, tokens)
            runCatching { core.library.refresh() }
            true
        }
        /** Stops awaitCode, which then fails as cancelled. */
        action("cancelCode") { awaiting?.cancel() }
        action("signOut") { core.session.signOut() }
        /**
         * Approves another device's sign-in code with this account's user, password and passcode
         * (SIGN-11), on the server this app is signed in to: true when it accepted them, false
         * when it refused the code or the account; fails when the server can't be reached.
         */
        call("link") { a ->
            core.api.link(
                core.session.endpoint, a.get("code"), a.get("user"), a.get("password"), a.get("passcode", ""),
            )
        }
        call("mediaAuthorization") { a -> core.session.mediaAuthorization(a.get("url")) }

        // ---- Library and music ----
        action("refreshLibrary") { a -> core.library.refresh(maxAgeMs = a.get("maxAgeMs", 60_000L)) }
        call("home") { core.library.home() }
        call("recentlyAdded") { core.music.recentlyAdded().first() }
        call("newReleases") { core.music.newReleases().first() }
        call("featured") { core.music.featured().first() }
        call("artists") { core.music.artists().first() }
        call("artistDetails") { a -> core.music.artistDetails(a.get("artistId")) }
        call("artistQueue") { a ->
            core.music.artistQueue(a.get("artistId"), a.get<ArtistMix>("mix"), a.get("startIndex", 0))
        }
        action("setArtistFavorite") { a -> core.music.setArtistFavorite(a.get("arid"), a.get("favorite")) }
        call("albumDetails") { a -> core.music.albumDetails(a.get("albumId")) }
        call("albumQueue") { a -> core.music.albumQueue(a.get("albumId"), a.get("startIndex", 0)) }
        call("search") { a -> core.music.search(a.get("query")) }
        call("trackQueue") { a -> core.music.trackQueue(a.get("trackId")) }
        call("tracksQueue") { a ->
            core.music.tracksQueue(a.get("title"), a.get<List<Track>>("tracks"), a.get("startIndex", 0))
        }
        call("refQueue") { a -> core.music.refQueue(a.get("ref"), a.get("title")) }
        call("radio") { core.music.radio() }
        call("stationQueue") { a -> core.music.stationQueue(a.get("stationId")) }
        call("radioNow") { core.music.radioNow() }
        call("replayQueue") { a ->
            core.history.find(a.get("id"))?.let { core.music.replayQueue(it) } ?: error("Gone from history")
        }
        // The live player's rules (LiveRadio), for Player.cpp.
        call("liveMore") { a -> core.music.liveMore(a.get<List<QueueItem>>("items")) }
        call("liveCheck") { a ->
            val now = Clock.System.now().toEpochMilliseconds()
            when (val start = LiveRadio.check(a.get("slot"), a.get("positionMs"), now, a.get("behindLive"))) {
                LiveStart.AsIs -> LiveAction("asIs")
                is LiveStart.SeekTo -> LiveAction("seek", start.positionMs)
                LiveStart.JumpToLive -> LiveAction("jump")
            }
        }
        call("liveResume") { a ->
            val slot = a.get<LiveSlot>("slot")
            val position = a.get<Long>("positionMs")
            val behind = LiveRadio.behindMs(slot, position, Clock.System.now().toEpochMilliseconds())
            when {
                behind <= LiveRadio.DriftMs -> LiveAction("live")
                LiveRadio.keepBehind(slot, position, behind) -> LiveAction("behind")
                else -> LiveAction("jump")
            }
        }
        call("playlists") { core.music.playlists() }
        call("playlistQueue") { a ->
            val id = a.get<String>("playlistId")
            // the whole playlist (its page): keep its covers for the Playlists grid
            core.music.playlistQueue(id, a.get("startIndex", 0)).also { core.music.keepCovers(id, it) }
        }
        call("playlistCovers") { a -> core.music.playlistCovers(a.get("playlistId"), a.get("trackCount")) }
        call("createPlaylist") { a -> core.music.createPlaylist(a.get("name")) }
        action("addTrackToPlaylist") { a -> core.music.addTrackToPlaylist(a.get("playlistId"), a.get("trackId")) }
        action("addAlbumToPlaylist") { a -> core.music.addAlbumToPlaylist(a.get("playlistId"), a.get("albumId")) }
        action("removeFromPlaylist") { a -> core.music.removeFromPlaylist(a.get("playlistId"), a.get("index")) }
        action("deletePlaylist") { a -> core.music.deletePlaylist(a.get("playlistId")) }
        call("favorites") { core.music.favorites() }
        call("favoritesQueue") { a -> core.music.favoritesQueue(a.get("startIndex", 0)) }
        action("loadFavoriteTracks") { core.music.loadFavoriteTracks() }
        action("setTrackFavorite") { a -> core.music.setTrackFavorite(a.get("etag"), a.get("favorite")) }
        call("activity") { a -> core.music.activity(a.get<ActivityInterval>("interval")) }
        call("recentTracks") { core.music.recentTracks() }

        // ---- Podcasts ----
        call("podcasts") { a -> core.podcasts.series(a.get("subscribedOnly", false)) }
        call("podcastDetails") { a -> core.podcasts.details(a.get("seriesId")) }
        action("setSubscribed") { a -> core.podcasts.setSubscribed(a.get("seriesId"), a.get("subscribed")) }
        call("episodesQueue") { a ->
            core.podcasts.episodesQueue(
                a.get("title"), a.get("imageUri", ""), a.get<List<PodcastEpisode>>("episodes"), a.get("startIndex", 0),
            )
        }
        action("markPlayed") { a ->
            val eid = a.get<String>("eid")
            val (offset, duration) = TakeoutPodcastRepository.playedMark(core.podcasts.offset(eid), a.get("played"))
            core.progress.report(eid, offset * 1000L, duration * 1000L)
        }

        // ---- Live ----
        // The guide page: guideReload starts over from now, guideEnsure fetches what scrolls
        // into view; each answers with everything fetched so far (null if nothing could be).
        // A reload that fails answers null, not the guide from before: the page keeps what it
        // shows and tries again a minute later. (Answered with the old guide, a reload woken
        // before the network looked fetched, and yesterday's guide stayed up for 30 minutes.)
        call("guideReload") {
            val loader = core.guideLoader
            if (!loader.reload()) return@call null
            val now = loader.guide.value!!.now()
            loader.ensure(now, now + GuideLoader.Block)
            loader.page()
        }
        call("guideEnsure") { a ->
            val loader = core.guideLoader
            loader.ensure(Instant.fromEpochMilliseconds(a.get("fromMs")), Instant.fromEpochMilliseconds(a.get("untilMs")))
            loader.page()
        }
        call("tune") { a -> core.liveGuide.get().tuneLive(a.get("channel")) }

        // ---- Video ----
        call("continueWatching") { core.video.continueWatching(ProgressRules.AllTime) }
        call("videoRecentlyAdded") { core.video.recentlyAdded() }
        call("videoNewReleases") { core.video.newReleases() }
        call("recentShows") { core.video.recentShows() }
        call("movies") { core.video.movies() }
        call("shows") { core.video.shows() }
        call("genres") { core.video.genres() }
        call("genre") { a -> core.video.genre(a.get("genreId")) }
        call("recommendations") { core.video.recommendations().first() }
        call("videoFavorites") { core.video.favorites() }
        call("videoDetails") { a -> core.video.details(a.get("id")) }
        call("person") { a -> core.video.person(a.get("personId")) }
        call("videoSearch") { a -> core.video.search(a.get("query")) }
        action("setVideoFavorite") { a -> core.video.setFavorite(a.get("path"), a.get("favorite")) }

        // ---- Progress, Recently Played and listens ----
        call("resumePositionMs") { a -> core.progress.resumePositionMs(a.get("etag")) }
        /** Where [etag] was left, when it's worth resuming (else null): for "N min left". */
        call("resumeOffset") { a ->
            core.progress.latest()[a.get<String>("etag")]?.takeIf { ProgressRules.shouldResume(it.offset, it.duration) }
        }
        /** mpv's audio tracks: the shared labels and the best one to play. */
        call("audioTracks") { a ->
            audioTracks(a.get<List<MpvAudioTrack>>("tracks"), a.get<String?>("preferredLanguage", null))
        }
        /** The subtitle track for a new title in the language picked last; null for none in it. */
        call("subtitleTrack") { a ->
            subtitleTrack(a.get<List<MpvSubtitleTrack>>("tracks"), a.get("language"))
        }
        action("reportProgress") { a ->
            core.progress.report(a.get("etag"), a.get("positionMs"), a.get("durationMs"))
        }
        call("recordPlayed") { a -> core.history.record(a.get<MusicQueue>("queue")) }
        action("playedProgress") { a -> core.history.progress(a.get("id"), a.get("index"), a.get("positionMs")) }
        action("clearRecentlyPlayed") { core.history.clear() }
        /** Clears Recently Played and Stream History on this device, as the phone's Clear does. */
        action("clearHistory") {
            core.history.clear()
            core.streamHistory.clear()
        }
        action("retryListens") { core.listenReporter.retry() }
        // What the player is doing, for ListenSession (see there).
        action("listenTrack") { a ->
            val item = a.get<QueueItem?>("item", null)
            core.playingEtag = item?.etag
            item?.etag?.let(core.downloads::touch)
            listen { track(item) }
        }
        action("listenPlaying") { a -> listen { playing(a.get("playing"), a.get("durationMs", 0L)) } }
        action("listenTick") { a -> listen { tick(a.get("durationMs", 0L)) } }
        action("listenStreamTitle") { a -> listen { streamTitle(a.get("title")) } }
        action("listenStopped") { listen { stopped() } }
        action("streamSongHeard") { a ->
            core.streamHistory.heard(a.get("station"), a.get("imageUri", ""), a.get("title"))
        }

        // ---- Downloads ----
        /** A queue's items (all, or [only] some), grouped with the queue. */
        action("downloadQueue") { a ->
            core.downloads.download(a.get<MusicQueue>("queue"), a.get<List<QueueItem>?>("only", null))
        }
        /**
         * A movie or episode, by id. With [check] (and not [anyway]), a file of known size first
         * gets room (cached tracks go); if even that isn't enough it isn't started, and the answer
         * says so, for the app to ask.
         */
        call("downloadVideo") { a ->
            val details = core.video.details(a.get("id"))
            if (a.get("check", false) && !a.get("anyway", false) && details.size > 0) {
                val room = core.cachePurge.makeRoom(details.size)
                if (!room.enough) return@call VideoDownload(false, details.size, room.freeableBytes, room.fits)
            }
            core.downloads.download(details)
            VideoDownload(true)
        }
        /**
         * Playback Caching: an item played to its end is downloaded (if it's on), marked cached,
         * grouped with the queue it played in (Recently Played's [historyId], else [queueTitle]).
         */
        action("keepPlayed") { a ->
            if (!core.settings.playbackCaching.value) return@action
            val item = a.get<QueueItem>("item")
            if (item.etag.isEmpty() || core.downloads.downloads.value.containsKey(item.etag)) return@action
            val played = a.get<String?>("historyId", null)?.let(core.history::find)
            val title = played?.title ?: a.get("queueTitle", "")
            val group = played?.location?.ifEmpty { null } ?: "title:$title"
            core.downloads.download(listOf(item), group, title, cached = true)
        }
        action("clearCached") { core.cachePurge.clearCached() }
        action("refreshCache") { core.cachePurge.refresh() }
        action("retryDownload") { a -> core.downloads.retry(a.get("etag")) }
        action("removeDownloads") { a -> core.downloads.remove(a.get<List<String>>("etags")) }
        action("removeAllDownloads") { core.downloads.removeAll() }

        // ---- Settings ----
        action("setSettings") { a ->
            a.get<Boolean?>("reportPlays", null)?.let(core.settings::setReportPlays)
            a.get<Boolean?>("submitListens", null)?.let(core.settings::setSubmitListens)
            a.get<Boolean?>("playbackCaching", null)?.let(core.settings::setPlaybackCaching)
            a.get<ThemeMode?>("theme", null)?.let(core.settings::setTheme)
            a.get<Boolean?>("repeatAll", null)?.let(core.settings::setRepeatAll)
            // 0 for Auto
            a.get<Long?>("cacheLimit", null)?.let { core.settings.setCacheLimit(if (it > 0) CacheLimit.Fixed(it) else CacheLimit.Auto) }
        }
        call("connectListenBrainz") { a -> core.listenBrainz.connect(a.get("token")) }
        action("disconnectListenBrainz") { core.listenBrainz.disconnect() }
    }

    companion object {
        /** Bumped when a call changes in a way the app must follow. */
        const val BridgeVersion = 1

        /**
         * The player's reports, which must arrive in the order it sent them (a track before its
         * play state, a position after the one before it); they don't suspend for long.
         */
        fun isOrdered(name: String): Boolean =
            name.startsWith("listen") || name == "playedProgress" || name == "reportProgress" ||
                name == "streamSongHeard"
    }
}

/** What [Player.cpp] does next on a live station: "asIs", "seek" (to [positionMs]), "jump", "live", "behind". */
@Serializable
data class LiveAction(val action: String, val positionMs: Long = 0)
