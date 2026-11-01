import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD
import TakeoutFM.Core

/** Favorite Tracks first, then the user's playlists. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Playlists")

    property var playlists: []
    property bool loading: false
    property var error: null

    function load() {
        loading = true
        error = null
        Core.call("playlists", {}, (result, failure) => {
            loading = false
            error = failure
            playlists = result ?? []
        })
    }
    Component.onCompleted: load()

    ListView {
        model: [{id: "", name: qsTr("Favorite Tracks"), trackCount: -1}].concat(page.playlists)
        delegate: KD.SubtitleDelegate {
            required property var modelData
            width: ListView.view.width
            text: modelData.name
            subtitle: modelData.trackCount === 1 ? qsTr("1 track") : modelData.trackCount > 1 ? qsTr("%1 tracks").arg(modelData.trackCount) : ""
            icon.name: modelData.id === "" ? "emblem-favorite" : "view-media-playlist"
            onClicked: applicationWindow().open("TracksPage", {title: modelData.name, playlistId: modelData.id})
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading && page.playlists.length === 0
            error: page.error
            onRetry: page.load()
        }
    }
}
