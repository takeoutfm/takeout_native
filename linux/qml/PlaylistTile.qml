import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * A playlist in the Playlists grid: its mosaic, name and track count; Favorite Tracks when
 * [playlist] has no id. Opens the playlist; right-click (or the menu key) offers Delete.
 */
QQC2.ItemDelegate {
    id: tile

    required property var playlist
    readonly property bool favorites: playlist.id === ""
    signal deleteRequested()

    padding: Kirigami.Units.smallSpacing
    onClicked: applicationWindow().open("TracksPage", {title: playlist.name, playlistId: playlist.id})

    // Its covers, kept in the core until its track count changes (so revisits don't refetch).
    property var covers: []
    function loadCovers() {
        if (favorites) return
        Core.call("playlistCovers", {playlistId: playlist.id, trackCount: playlist.trackCount},
                  (result) => { if (result) tile.covers = result })
    }
    Component.onCompleted: loadCovers()
    Connections {
        target: tile
        function onPlaylistChanged() { tile.loadCovers() }
    }

    TapHandler {
        acceptedButtons: Qt.RightButton
        enabled: !tile.favorites
        onTapped: menu.popup()
    }
    Keys.onMenuPressed: if (!favorites) menu.popup()
    QQC2.Menu {
        id: menu
        QQC2.MenuItem {
            text: KI18n.i18n("Delete Playlist…")
            icon.name: "edit-delete"
            onTriggered: tile.deleteRequested()
        }
    }

    contentItem: ColumnLayout {
        spacing: Kirigami.Units.smallSpacing

        Loader {
            Layout.fillWidth: true
            Layout.preferredHeight: width
            sourceComponent: tile.favorites ? heart : mosaicArt
        }
        Component { id: heart; FavoriteTracksArt {} }
        Component { id: mosaicArt; PlaylistMosaic { covers: tile.covers } }

        QQC2.Label {
            Layout.fillWidth: true
            text: tile.playlist.name
            elide: Text.ElideRight
            font.weight: Font.DemiBold
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: tile.favorites ? KI18n.i18n("Tracks you ♥") : KI18n.i18np("1 track", "%1 tracks", tile.playlist.trackCount)
            elide: Text.ElideRight
            opacity: 0.7
        }
    }
}
