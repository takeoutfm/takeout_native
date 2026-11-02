import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD
import TakeoutFM
import org.kde.ki18n

/** Favorite Tracks first, then the user's playlists. */
AppPage {
    id: page

    title: KI18n.i18n("Playlists")

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
        model: [{id: "", name: KI18n.i18n("Favorite Tracks"), trackCount: -1}].concat(page.playlists)
        // No row is current (or looks selected) until the arrow keys pick one; the list is
        // given focus when the page opens, and a new model would make the first row current.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true
        delegate: KD.SubtitleDelegate {
            required property var modelData
            width: ListView.view.width
            // Only the current row holds the list's focus (a reset leaves it on the first).
            focus: ListView.isCurrentItem
            text: modelData.name
            subtitle: modelData.trackCount > 0 ? KI18n.i18np("1 track", "%1 tracks", modelData.trackCount) : ""
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
