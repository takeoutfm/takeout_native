import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * A playlist's tracks, or Favorite Tracks when [playlistId] is empty, under a header as an
 * album's (as on the Mac and the phone): its mosaic beside the name and track count. A
 * playlist's menu deletes it.
 */
AppPage {
    id: page

    required property string playlistId
    /** The tracks as a play queue (a MusicQueue). */
    property var queue: null
    readonly property var tracks: queue?.items ?? []
    property bool loading: false
    property var error: null

    function load() {
        loading = true
        error = null
        const [name, args] = playlistId === "" ? ["favoritesQueue", {}] : ["playlistQueue", {playlistId: playlistId}]
        Core.call(name, args, (result, failure) => {
            loading = false
            error = failure
            if (result) queue = result
            // kept by the core as it loaded the playlist
            if (result && playlistId !== "")
                Core.call("playlistCovers", {playlistId: playlistId, trackCount: result.items.length},
                          (c) => { if (c) page.covers = c })
        })
    }
    property var covers: []
    Component.onCompleted: load()

    // A track added or removed (here or anywhere) changes the shared count: load again (decision 017).
    Connections {
        target: Core
        enabled: page.playlistId !== ""
        function onPlaylistsChanged() {
            const count = (Core.playlists ?? []).find(p => p.id === page.playlistId)?.trackCount
            if (page.queue && count !== undefined && count !== page.tracks.length) page.load()
        }
    }

    actions: [
        Kirigami.Action {
            text: KI18n.i18n("Play")
            icon.name: "media-playback-start"
            enabled: page.tracks.length > 0
            onTriggered: Player.play(page.queue, false, 0)
        },
        Kirigami.Action {
            text: KI18n.i18n("Shuffle")
            icon.name: "media-playlist-shuffle"
            enabled: page.tracks.length > 0
            onTriggered: Player.play(page.queue, true)
        },
        Kirigami.Action {
            readonly property var etags: page.tracks.map(t => t.etag).filter(e => e)
            readonly property bool all: etags.length > 0 && etags.every(e => Core.downloadsByEtag[e]?.state === "Done")
            text: all ? KI18n.i18n("Remove Download") : KI18n.i18n("Download")
            icon.name: all ? "edit-delete-remove" : "download"
            displayHint: Kirigami.DisplayHint.AlwaysHide
            enabled: etags.length > 0
            onTriggered: all ? Core.call("removeDownloads", {etags: etags}) : Core.call("downloadQueue", {queue: page.queue})
        },
        Kirigami.Action {
            text: KI18n.i18n("Delete Playlist…")
            icon.name: "edit-delete"
            displayHint: Kirigami.DisplayHint.AlwaysHide
            visible: page.playlistId !== ""
            onTriggered: {
                deleting.playlist = {id: page.playlistId, name: page.title}
                deleting.open()
            }
        }
    ]

    DeletePlaylistDialog {
        id: deleting
        onDeleted: applicationWindow().pageStack.pop()
    }

    ListView {
        model: page.tracks
        header: RowLayout {
            width: ListView.view.width
            spacing: Kirigami.Units.largeSpacing * 2
            Loader {
                Layout.margins: Kirigami.Units.largeSpacing
                Layout.preferredWidth: Kirigami.Units.gridUnit * 10
                Layout.preferredHeight: Kirigami.Units.gridUnit * 10
                sourceComponent: page.playlistId === "" ? heart : mosaic
            }
            Component { id: heart; FavoriteTracksArt {} }
            Component { id: mosaic; PlaylistMosaic { covers: page.covers } }
            ColumnLayout {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignBottom
                Layout.bottomMargin: Kirigami.Units.largeSpacing
                Kirigami.Heading {
                    Layout.fillWidth: true
                    text: page.title
                    wrapMode: Text.Wrap
                }
                QQC2.Label {
                    visible: !!page.queue
                    text: KI18n.i18np("1 track", "%1 tracks", page.tracks.length)
                    opacity: 0.7
                }
            }
        }
        reuseItems: true
        // No row is current (or looks selected) until the arrow keys pick one; the list is
        // given focus when the page opens, and a new model would make the first row current.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true
        delegate: TrackRow {
            required property var modelData
            required property int index
            // Only the current row holds the list's focus (a reset leaves it on the first).
            focus: ListView.isCurrentItem
            onClicked: Player.play(page.queue, false, index)
            etag: modelData.etag
            // Playlist entries come without a track id: queued as they are (no Add to Playlist).
            queueItem: modelData
            title: modelData.title
            subtitle: [modelData.artist, modelData.album].filter(s => s.length > 0).join(" · ")
            imageUri: modelData.imageUri
            // LIB-13: by its place in the playlist; the shared count then reloads the page.
            removable: page.playlistId !== ""
            onRemove: Core.call("removeFromPlaylist", {playlistId: page.playlistId, index: index}, (r, error) => {
                if (error) applicationWindow().showPassiveNotification(KI18n.i18n("Couldn't remove %1", modelData.title))
            })
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading && !page.queue
            error: page.queue ? null : page.error
            onRetry: page.load()
        }
        Kirigami.PlaceholderMessage {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.gridUnit * 4
            visible: !page.loading && !page.error && page.tracks.length === 0
            text: KI18n.i18n("No tracks")
        }
    }
}
