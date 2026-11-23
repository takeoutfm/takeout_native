import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * Favorite Tracks, then the user's playlists A–Z, as a grid of covers (as on the Mac and the
 * phone): each playlist's art is a mosaic of its first four albums' covers. New Playlist in the
 * toolbar; a playlist's right-click menu deletes it.
 */
AppPage {
    id: page

    title: KI18n.i18n("Playlists")

    property bool loading: false
    property var error: null

    // The core's playlists (decision 017): creating, deleting or adding to one anywhere shows here.
    readonly property var playlists: Core.playlists ?? []

    // Each visit asks the server again, for changes made in another app.
    function load() {
        loading = true
        error = null
        Core.call("playlists", {}, (result, failure) => {
            loading = false
            error = failure
        })
    }
    Component.onCompleted: load()

    actions: [
        Kirigami.Action {
            text: KI18n.i18n("New Playlist")
            icon.name: "list-add"
            onTriggered: {
                newName.text = ""
                newPlaylist.open()
            }
        }
    ]

    GridView {
        id: grid
        // No tile is current (or looks selected) until the arrow keys pick one; the grid is
        // given focus when the page opens, and a new model would make the first tile current.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true
        readonly property int columns: Math.max(1, Math.floor(width / (Kirigami.Units.gridUnit * 10)))
        cellWidth: width / columns
        cellHeight: cellWidth + Kirigami.Units.gridUnit * 3
        model: [{id: "", name: KI18n.i18n("Favorite Tracks"), trackCount: 0}].concat(page.playlists)
        delegate: PlaylistTile {
            required property var modelData
            width: grid.cellWidth
            // Only the current tile holds the grid's focus (else the first looks selected).
            focus: GridView.isCurrentItem
            playlist: modelData
            onDeleteRequested: {
                deleting.playlist = modelData
                deleting.open()
            }
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading && !Core.playlists
            error: Core.playlists ? null : page.error
            onRetry: page.load()
        }
    }

    Kirigami.PromptDialog {
        id: newPlaylist
        title: KI18n.i18n("New Playlist")
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        QQC2.TextField {
            id: newName
            placeholderText: KI18n.i18n("Playlist name")
            onAccepted: newPlaylist.accept()
        }
        onAccepted: {
            const value = newName.text.trim()
            if (value.length === 0) return
            Core.call("createPlaylist", {name: value}, (playlist, error) => {
                if (!playlist) applicationWindow().showPassiveNotification(KI18n.i18n("Couldn't create that playlist"))
            })
        }
    }

    DeletePlaylistDialog { id: deleting }
}
