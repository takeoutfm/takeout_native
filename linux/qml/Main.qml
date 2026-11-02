import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

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
    property string section: ""

    /** Shows top-level page [name] (e.g. "HomePage"), dropping whatever was open. */
    function show(name, properties) {
        section = name
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
            showPassiveNotification(qsTr("Downloading %1").arg(only === undefined ? queue.title : queue.items[only].title))
        })
    }

    /** Plays the queue that shared call [name] returns (albumQueue, stationQueue…). */
    function playCall(name, args, shuffled) {
        Core.call(name, args, (queue, error) => {
            if (queue) Player.play(queue, shuffled ?? false)
            else if (error && error.kind !== "network") showPassiveNotification(qsTr("Can't play that"))
        })
    }

    /** Now Playing, on top unless it already is. */
    function showNowPlaying() {
        if (pageStack.currentItem?.objectName !== "nowPlaying") open("NowPlayingPage")
    }

    // Under the pages, beside the drawer (Kirigami lays a window footer out under the drawer, offset).
    pageStack.anchors.bottomMargin: miniPlayer.height
    MiniPlayer {
        id: miniPlayer
        parent: root.contentItem
        anchors.left: root.pageStack.left
        anchors.right: root.pageStack.right
        anchors.bottom: parent.bottom
        visible: Core.signedIn && Player.hasQueue && root.pageStack.currentItem?.objectName !== "nowPlaying"
        onOpen: root.showNowPlaying()
    }

    // Sign-in or Home, once the session is known and whenever signing in or out changes it
    // (the session also changes when tokens are refreshed).
    property var routedFor: undefined
    function route() {
        if (!Core.ready || routedFor === Core.signedIn) return
        routedFor = Core.signedIn
        if (Core.signedIn) {
            show("HomePage")
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
            if (error.kind === "network") root.showPassiveNotification(qsTr("Can't reach the server"))
        }
    }

    globalDrawer: Kirigami.GlobalDrawer {
        // Hidden (modal and closed, no handle) until signed in.
        isMenu: false
        modal: !Core.signedIn || !root.wideScreen
        handleVisible: Core.signedIn && modal
        enabled: Core.signedIn
        collapsible: false
        header: Kirigami.SearchField {
            id: search
            placeholderText: qsTr("Search")
            onAccepted: if (text.trim().length > 0) root.show("SearchPage", {query: text.trim()})
        }
        actions: [
            Kirigami.Action {
                text: qsTr("Home"); icon.name: "go-home"
                checked: root.section === "HomePage"
                onTriggered: root.show("HomePage")
            },
            Kirigami.Action {
                text: qsTr("Now Playing"); icon.name: "media-playback-playing"
                visible: Player.hasQueue
                onTriggered: root.showNowPlaying()
            },
            Kirigami.Action {
                text: qsTr("Recent"); icon.name: "document-open-recent"
                visible: root.hasMusic
                checked: root.section === "RecentPage"
                onTriggered: root.show("RecentPage")
            },
            Kirigami.Action {
                text: qsTr("Artists"); icon.name: "view-media-artist"
                visible: root.hasMusic
                checked: root.section === "ArtistsPage"
                onTriggered: root.show("ArtistsPage")
            },
            Kirigami.Action {
                text: qsTr("Radio"); icon.name: "radio"
                visible: root.hasMusic
                checked: root.section === "RadioPage"
                onTriggered: root.show("RadioPage")
            },
            Kirigami.Action {
                text: qsTr("Playlists"); icon.name: "view-media-playlist"
                visible: root.hasMusic
                checked: root.section === "PlaylistsPage"
                onTriggered: root.show("PlaylistsPage")
            },
            Kirigami.Action {
                text: qsTr("Movies"); icon.name: "video-x-generic"
                visible: root.hasMovies
                checked: root.section === "MoviesPage"
                onTriggered: root.show("MoviesPage")
            },
            Kirigami.Action {
                text: qsTr("TV"); icon.name: "video-television"
                visible: root.hasShows
                checked: root.section === "ShowsPage"
                onTriggered: root.show("ShowsPage")
            },
            Kirigami.Action {
                text: qsTr("Podcasts"); icon.name: "podcast-symbolic"
                visible: root.hasPodcasts
                checked: root.section === "PodcastsPage"
                onTriggered: root.show("PodcastsPage")
            },
            Kirigami.Action {
                text: qsTr("Downloads"); icon.name: "download"
                checked: root.section === "DownloadsPage"
                onTriggered: root.show("DownloadsPage")
            },
            Kirigami.Action {
                text: qsTr("Settings"); icon.name: "settings-configure"
                checked: root.section === "SettingsPage"
                onTriggered: root.show("SettingsPage")
            }
        ]
    }
}
