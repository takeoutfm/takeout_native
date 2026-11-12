import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

Kirigami.ApplicationWindow {
    id: root

    title: "TakeoutFM"
    width: Kirigami.Units.gridUnit * 60
    height: Kirigami.Units.gridUnit * 42
    minimumWidth: Kirigami.Units.gridUnit * 22
    minimumHeight: Kirigami.Units.gridUnit * 20

    pageStack.columnView.columnResizeMode: Kirigami.ColumnView.SingleColumn
    pageStack.globalToolBar.showNavigationButtons: Kirigami.ApplicationHeaderStyle.ShowBackButton

    readonly property var library: Core.library
    readonly property bool hasMusic: library.hasMusic ?? true
    readonly property bool hasPodcasts: library.hasPodcasts ?? false
    readonly property bool hasMovies: library.hasMovies ?? false
    readonly property bool hasShows: library.hasShows ?? false
    readonly property bool hasGuide: library.hasGuide ?? false

    /** The video player closed: resume points and Continue Watching may have moved. */
    signal videoClosed()

    /** Plays movie, series or episode [id] over the whole window; from its start when [startOver]. */
    function playVideo(id, startOver) {
        videoPlayer.play(id, startOver ?? false)
    }

    VideoPlayer {
        id: videoPlayer
        onClosedVideo: root.videoClosed()
    }

    /** Plays channel [name] live over the whole window. */
    function playLive(name) {
        livePlayer.play(name)
    }

    LivePlayer {
        id: livePlayer
    }
    property string section: ""

    /**
     * Shows top-level page [name] (e.g. "RecentPage"), dropping whatever was open; [key] tells
     * the sidebar which entry it is when one page serves several (album and poster grids).
     */
    function show(name, properties, key) {
        section = key ?? name
        pageStack.clear()
        pageStack.push(Qt.resolvedUrl(name + ".qml"), properties ?? {})
    }

    /** Opens [name] on top of the current page. */
    function open(name, properties) {
        pageStack.push(Qt.resolvedUrl(name + ".qml"), properties ?? {})
    }

    /** Bytes as "12 MB" / "1.4 GB". */
    function formatBytes(n) {
        const KB = 1024, MB = KB * 1024, GB = MB * 1024
        if (n >= GB) return (Math.round(n * 10 / GB) / 10).toFixed(1) + " GB"
        if (n >= MB) return Math.floor(n / MB) + " MB"
        return Math.max(1, Math.floor(n / KB)) + " KB"
    }

    /** Downloads the queue that shared call [name] returns (all of it, or item [only]). */
    function downloadCall(name, args, only) {
        Core.call(name, args, (queue) => {
            if (!queue) return
            Core.call("downloadQueue", only === undefined ? {queue: queue} : {queue: queue, only: [queue.items[only]]})
            showPassiveNotification(KI18n.i18n("Downloading %1", only === undefined ? queue.title : queue.items[only].title))
        })
    }

    /**
     * Plays track [trackId] next, or adds it at the end ([last]): [queueItem] as it is when the
     * row has one, else the track alone from its play queue (which goes on with similar tracks).
     */
    function enqueueTrack(trackId, queueItem, last) {
        const done = (item) => {
            Player.enqueue([item], last)
            showPassiveNotification(last ? KI18n.i18n("Added to Queue") : KI18n.i18n("Playing Next"))
        }
        if (queueItem) return done(queueItem)
        Core.call("trackQueue", {trackId: trackId}, (queue) => {
            if (queue?.items.length) done(queue.items[0])
            else showPassiveNotification(KI18n.i18n("Couldn't add that"))
        })
    }

    /** Asks which playlist to add track [trackId] ([title]) to. */
    function addToPlaylist(trackId, title) {
        playlistPicker.pick(trackId, title)
    }

    AddToPlaylistDialog { id: playlistPicker }

    /** Plays the queue that shared call [name] returns (albumQueue, stationQueue…). */
    function playCall(name, args, shuffled) {
        Core.call(name, args, (queue, error) => {
            if (queue) Player.play(queue, shuffled ?? false)
            else if (error && error.kind !== "network") showPassiveNotification(KI18n.i18n("Can't play that"))
        })
    }

    /** Now Playing, on top unless it already is. */
    function showNowPlaying() {
        if (pageStack.currentItem?.objectName !== "nowPlaying") open("NowPlayingPage")
    }

    // The Now Playing bar is each page's footer (AppPage).

    // Sign-in or Recently Played (the Mac's first page), once the session is known and whenever signing in or out changes it
    // (the session also changes when tokens are refreshed).
    property var routedFor: undefined
    function route() {
        if (!Core.ready || routedFor === Core.signedIn) return
        routedFor = Core.signedIn
        if (Core.signedIn) {
            show("RecentPage")
            Core.call("refreshLibrary", {maxAgeMs: 0})
        } else {
            show("SignInPage")
        }
    }
    Component.onCompleted: route()

    Connections {
        target: Core
        function onSessionChanged() { root.route() }
        function onFailed(name, error) {
            if (error.kind === "network") root.showPassiveNotification(KI18n.i18n("Can't reach the server"))
        }
    }

    /** A page in the sidebar: [page] (a QML file) with [properties], selected while [key] is shown. */
    component PageAction: Kirigami.Action {
        property string page
        property var properties: ({})
        property string key: page
        checked: root.section === key
        onTriggered: root.show(page, properties, key)
    }

    globalDrawer: Kirigami.GlobalDrawer {
        // Hidden (modal and closed, no handle) until signed in.
        isMenu: false
        modal: !Core.signedIn || !root.wideScreen
        handleVisible: Core.signedIn && modal
        // Kirigami opens a sidebar only when the window starts: one that becomes a sidebar
        // later (on signing in, or widening a narrow window) would stay closed for good.
        onModalChanged: if (!modal) open()
        enabled: Core.signedIn
        collapsible: false
        header: Kirigami.SearchField {
            id: search
            // Searches the server on Enter, not as each letter is typed (Kirigami's default).
            autoAccept: false
            placeholderText: KI18n.i18n("Artists, albums, tracks")
            onAccepted: {
                const query = text.trim()
                if (query.length === 0) return
                root.show("SearchPage", {query: query}, "search")
            }
        }

        // The Mac's sidebar in Kirigami's own: music, video, this computer and the last search,
        // the groups set apart by separators.
        actions: [
            PageAction {
                visible: root.hasMusic
                text: KI18n.i18n("Recently Played"); icon.name: "document-open-recent"
                page: "RecentPage"
            },
            PageAction {
                visible: root.hasMusic
                text: KI18n.i18n("Recently Added"); icon.name: "list-add"
                page: "AlbumGridPage"; key: "recentlyAdded"
                properties: ({title: KI18n.i18n("Recently Added"), call: "recentlyAdded"})
            },
            PageAction {
                visible: root.hasMusic
                text: KI18n.i18n("New Releases"); icon.name: "media-album-cover"
                page: "AlbumGridPage"; key: "newReleases"
                properties: ({title: KI18n.i18n("New Releases"), call: "newReleases"})
            },
            PageAction {
                visible: root.hasMusic
                text: KI18n.i18n("Artists"); icon.name: "view-media-artist"
                page: "ArtistsPage"
            },
            PageAction {
                visible: root.hasMusic
                text: KI18n.i18n("Favorites"); icon.name: "emblem-favorite"
                page: "FavoritesPage"
            },
            PageAction {
                visible: root.hasMusic && (root.library.hasPlaylists ?? true)
                text: KI18n.i18n("Playlists"); icon.name: "view-media-playlist"
                page: "PlaylistsPage"
            },
            PageAction {
                visible: root.hasMusic
                text: KI18n.i18n("Radio"); icon.name: "radio"
                page: "RadioPage"
            },
            PageAction {
                visible: root.hasPodcasts
                text: KI18n.i18n("Podcasts"); icon.name: "podcast-symbolic"
                page: "PodcastsPage"
            },
            Kirigami.Action { separator: true; visible: root.hasMovies || root.hasShows },
            PageAction {
                visible: root.hasMovies || root.hasShows
                text: KI18n.i18n("Watch"); icon.name: "video-display"
                page: "WatchPage"
            },
            PageAction {
                visible: root.hasGuide
                text: KI18n.i18n("Live"); icon.name: "media-record"
                page: "LivePage"
            },
            PageAction {
                visible: root.hasMovies
                text: KI18n.i18n("Movies"); icon.name: "video-x-generic"
                page: "VideoGridPage"; key: "movies"
                properties: ({title: KI18n.i18n("Movies"), call: "movies"})
            },
            PageAction {
                visible: root.hasShows
                text: KI18n.i18n("TV Shows"); icon.name: "video-television"
                page: "VideoGridPage"; key: "shows"
                properties: ({title: KI18n.i18n("TV Shows"), call: "shows"})
            },
            Kirigami.Action { separator: true },
            PageAction {
                text: KI18n.i18n("Downloads"); icon.name: "download"
                page: "DownloadsPage"
            },
            Kirigami.Action { separator: true },
            PageAction {
                text: KI18n.i18n("Settings"); icon.name: "settings-configure"
                page: "SettingsPage"
            }
        ]
    }
}
