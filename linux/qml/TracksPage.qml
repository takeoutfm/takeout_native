import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** A playlist's tracks, or Favorite Tracks when [playlistId] is empty. */
Kirigami.ScrollablePage {
    id: page

    required property string playlistId
    property var tracks: []
    property bool loading: false
    property var error: null

    function load() {
        loading = true
        error = null
        if (playlistId === "") {
            Core.call("favorites", {}, (result, failure) => {
                loading = false
                error = failure
                tracks = (result?.tracks ?? []).map(t => ({title: t.title, artist: t.artist, album: t.album, imageUri: t.coverUri, etag: t.etag}))
            })
        } else {
            Core.call("playlistQueue", {playlistId: playlistId}, (result, failure) => {
                loading = false
                error = failure
                tracks = result?.items ?? []
            })
        }
    }
    Component.onCompleted: load()

    ListView {
        model: page.tracks
        reuseItems: true
        delegate: TrackRow {
            required property var modelData
            title: modelData.title
            subtitle: [modelData.artist, modelData.album].filter(s => s.length > 0).join(" · ")
            imageUri: modelData.imageUri
            favorite: Core.favoriteTracks.includes(modelData.etag)
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading
            error: page.error
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
