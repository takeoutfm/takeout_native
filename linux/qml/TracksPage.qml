import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** A playlist's tracks, or Favorite Tracks when [playlistId] is empty. */
Kirigami.ScrollablePage {
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
        })
    }
    Component.onCompleted: load()

    actions: [
        Kirigami.Action {
            text: qsTr("Play")
            icon.name: "media-playback-start"
            enabled: page.tracks.length > 0
            onTriggered: Player.play(page.queue, false, 0)
        },
        Kirigami.Action {
            text: qsTr("Shuffle")
            icon.name: "media-playlist-shuffle"
            enabled: page.tracks.length > 0
            onTriggered: Player.play(page.queue, true)
        },
        Kirigami.Action {
            readonly property var etags: page.tracks.map(t => t.etag).filter(e => e)
            readonly property bool all: etags.length > 0 && etags.every(e => Core.downloadsByEtag[e]?.state === "Done")
            text: all ? qsTr("Remove Download") : qsTr("Download")
            icon.name: all ? "edit-delete-remove" : "download"
            displayHint: Kirigami.DisplayHint.AlwaysHide
            enabled: etags.length > 0
            onTriggered: all ? Core.call("removeDownloads", {etags: etags}) : Core.call("downloadQueue", {queue: page.queue})
        }
    ]

    ListView {
        model: page.tracks
        reuseItems: true
        delegate: TrackRow {
            required property var modelData
            required property int index
            onClicked: Player.play(page.queue, false, index)
            etag: modelData.etag
            title: modelData.title
            subtitle: [modelData.artist, modelData.album].filter(s => s.length > 0).join(" · ")
            imageUri: modelData.imageUri
            favorite: Core.favoriteTracks.includes(modelData.etag)
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
            text: qsTr("No tracks")
        }
    }
}
