import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD
import TakeoutFM
import org.kde.ki18n

/** Adds a track to one of the user's playlists, or to a new one, as the phone's picker. */
Kirigami.Dialog {
    id: dialog

    property string trackId
    property string trackTitle
    property var playlists: null

    title: KI18n.i18n("Add to Playlist")
    standardButtons: Kirigami.Dialog.Cancel
    preferredWidth: Kirigami.Units.gridUnit * 22
    padding: 0

    /** Opens the picker for track [id] ([title] for the message once it's added). */
    function pick(id, title) {
        trackId = id
        trackTitle = title
        playlists = null
        Core.call("playlists", {}, (result) => playlists = result ?? [])
        open()
    }

    function addTo(playlist) {
        close()
        Core.call("addTrackToPlaylist", {playlistId: playlist.id, trackId: trackId}, (r, error) => {
            applicationWindow().showPassiveNotification(error ? KI18n.i18n("Couldn't add that")
                                                              : KI18n.i18n("Added to %1", playlist.name))
        })
    }

    ColumnLayout {
        spacing: 0

        QQC2.BusyIndicator {
            Layout.alignment: Qt.AlignHCenter
            visible: dialog.playlists === null
            running: visible
        }
        KD.SubtitleDelegate {
            Layout.fillWidth: true
            visible: dialog.playlists !== null
            text: KI18n.i18n("New Playlist…")
            icon.name: "list-add"
            onClicked: {
                name.text = ""
                newPlaylist.open()
            }
        }
        Repeater {
            model: dialog.playlists ?? []
            KD.SubtitleDelegate {
                required property var modelData
                Layout.fillWidth: true
                text: modelData.name
                subtitle: KI18n.i18np("1 track", "%1 tracks", modelData.trackCount)
                icon.name: "view-media-playlist"
                onClicked: dialog.addTo(modelData)
            }
        }
    }

    Kirigami.PromptDialog {
        id: newPlaylist
        title: KI18n.i18n("New Playlist")
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        QQC2.TextField {
            id: name
            placeholderText: KI18n.i18n("Playlist name")
            onAccepted: newPlaylist.accept()
        }
        onAccepted: {
            const value = name.text.trim()
            if (value.length === 0) return
            Core.call("createPlaylist", {name: value}, (playlist, error) => {
                if (playlist) dialog.addTo(playlist)
                else applicationWindow().showPassiveNotification(KI18n.i18n("Couldn't create that playlist"))
            })
        }
    }
}
