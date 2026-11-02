import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * A track in a list: its number (or cover), title and a second line. Given a track ([trackId],
 * or the [queueItem] it plays as), it has the Mac's ♥ (shown while it's a favorite or under the
 * pointer) and a menu — right-click, or ⋮ — to play it, play it next, add it to the queue or a
 * playlist, and favorite it.
 */
QQC2.ItemDelegate {
    id: row

    property string number: ""
    property string title
    property string subtitle: ""
    property string imageUri: ""
    /** The track ("t-12"), for its menu; "" for rows that aren't tracks (podcast episodes). */
    property string trackId: ""
    /** The track as it would play, when the row has it (playlists): queued as it is. */
    property var queueItem: null
    readonly property bool isTrack: trackId.length > 0 || queueItem !== null
    readonly property bool favorite: isTrack && etag.length > 0 && Core.favoriteTracks.includes(etag)
    /** Shows whether it's downloaded (or how far along) when given. */
    property string etag: ""
    readonly property var download: etag ? Core.downloadsByEtag[etag] : undefined
    /** A control at the row's end (a podcast episode's Download). */
    property Component trailing: null

    Layout.fillWidth: true
    width: ListView.view ? ListView.view.width : implicitWidth

    contentItem: RowLayout {
        spacing: Kirigami.Units.largeSpacing

        QQC2.Label {
            visible: row.number.length > 0
            text: row.number
            opacity: 0.6
            horizontalAlignment: Text.AlignRight
            Layout.preferredWidth: Kirigami.Units.gridUnit * 1.5
        }
        Image {
            visible: row.imageUri.length > 0
            source: row.imageUri
            asynchronous: true
            fillMode: Image.PreserveAspectCrop
            Layout.preferredWidth: Kirigami.Units.iconSizes.medium
            Layout.preferredHeight: Kirigami.Units.iconSizes.medium
            sourceSize.width: width * Screen.devicePixelRatio
            sourceSize.height: height * Screen.devicePixelRatio
        }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 0
            QQC2.Label {
                Layout.fillWidth: true
                text: row.title
                elide: Text.ElideRight
            }
            QQC2.Label {
                Layout.fillWidth: true
                text: row.subtitle
                elide: Text.ElideRight
                opacity: 0.7
                visible: text.length > 0
                font: Kirigami.Theme.smallFont
            }
        }
        // Downloaded, downloading or failed.
        QQC2.BusyIndicator {
            visible: row.download?.state === "Queued" || row.download?.state === "Downloading"
            running: visible
            Layout.preferredWidth: Kirigami.Units.iconSizes.small
            Layout.preferredHeight: Kirigami.Units.iconSizes.small
        }
        Kirigami.Icon {
            visible: row.download?.state === "Done" || row.download?.state === "Failed"
            source: row.download?.state === "Failed" ? "data-warning" : "download"
            Layout.preferredWidth: Kirigami.Units.iconSizes.small
            Layout.preferredHeight: Kirigami.Units.iconSizes.small
        }
        Loader {
            active: row.trailing !== null
            sourceComponent: row.trailing
        }
        QQC2.ToolButton {
            visible: row.isTrack && row.etag.length > 0 && (row.favorite || row.hovered)
            icon.name: row.favorite ? "emblem-favorite" : "emblem-favorite-symbolic"
            text: row.favorite ? KI18n.i18n("Remove from Favorites") : KI18n.i18n("Add to Favorites")
            display: QQC2.AbstractButton.IconOnly
            // No taller than a line of text, so the row keeps its height.
            icon.width: Kirigami.Units.iconSizes.small
            icon.height: Kirigami.Units.iconSizes.small
            padding: 1
            Layout.maximumHeight: Kirigami.Units.gridUnit
            QQC2.ToolTip.text: text
            QQC2.ToolTip.visible: hovered
            QQC2.ToolTip.delay: Kirigami.Units.toolTipDelay
            onClicked: row.toggleFavorite()
        }
        QQC2.ToolButton {
            visible: row.isTrack
            // Kept in the layout, so the row doesn't shift as the pointer comes and goes.
            opacity: row.hovered || menu.visible ? 1 : 0
            icon.name: "overflow-menu"
            text: KI18n.i18n("More")
            display: QQC2.AbstractButton.IconOnly
            // No taller than a line of text, so the row keeps its height.
            icon.width: Kirigami.Units.iconSizes.small
            icon.height: Kirigami.Units.iconSizes.small
            padding: 1
            Layout.maximumHeight: Kirigami.Units.gridUnit
            QQC2.ToolTip.text: text
            QQC2.ToolTip.visible: hovered
            QQC2.ToolTip.delay: Kirigami.Units.toolTipDelay
            onClicked: menu.popup(this, 0, height)
        }
    }

    function toggleFavorite() {
        Core.call("setTrackFavorite", {etag: row.etag, favorite: !row.favorite})
    }

    TapHandler {
        enabled: row.isTrack
        acceptedButtons: Qt.RightButton
        onTapped: menu.popup()
    }

    QQC2.Menu {
        id: menu
        QQC2.MenuItem {
            text: KI18n.i18n("Play")
            icon.name: "media-playback-start"
            onTriggered: row.clicked()
        }
        QQC2.MenuItem {
            text: KI18n.i18n("Play Next")
            icon.name: "media-playlist-play"
            onTriggered: applicationWindow().enqueueTrack(row.trackId, row.queueItem, false)
        }
        QQC2.MenuItem {
            text: KI18n.i18n("Add to Queue")
            icon.name: "media-playlist-append"
            onTriggered: applicationWindow().enqueueTrack(row.trackId, row.queueItem, true)
        }
        QQC2.MenuSeparator {}
        QQC2.MenuItem {
            // Needs the track's id, which playlist entries don't have.
            visible: row.trackId.length > 0
            height: visible ? implicitHeight : 0
            text: KI18n.i18n("Add to Playlist…")
            icon.name: "list-add"
            onTriggered: applicationWindow().addToPlaylist(row.trackId, row.title)
        }
        QQC2.MenuItem {
            visible: row.etag.length > 0
            height: visible ? implicitHeight : 0
            text: row.favorite ? KI18n.i18n("Remove from Favorites") : KI18n.i18n("Add to Favorites")
            icon.name: row.favorite ? "emblem-favorite" : "emblem-favorite-symbolic"
            onTriggered: row.toggleFavorite()
        }
    }
}
