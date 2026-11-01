import QtQuick
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/** A titled, wrapping row of album tiles; hidden when [albums] is empty. */
ColumnLayout {
    id: flow

    property string title
    property var albums: []
    /** Show each album's year instead of its artist (on the artist's page). */
    property bool showYear: false

    visible: albums.length > 0
    Layout.fillWidth: true
    spacing: Kirigami.Units.smallSpacing

    SectionHeading { text: flow.title; visible: flow.title.length > 0 }

    Flow {
        id: tiles
        Layout.fillWidth: true
        readonly property int columns: Math.max(1, Math.floor(flow.width / (Kirigami.Units.gridUnit * 9)))

        Repeater {
            model: flow.albums
            AlbumTile {
                required property var modelData
                album: modelData
                subtitle: flow.showYear ? modelData.year : modelData.artist
                width: flow.width / tiles.columns
            }
        }
    }
}
