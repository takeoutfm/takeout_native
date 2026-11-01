import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/** An album's cover, name and artist (or year); opens the album. */
QQC2.ItemDelegate {
    id: tile

    required property var album
    /** The second line: the artist, or the year on an artist's own page. */
    property string subtitle: album.artist

    padding: Kirigami.Units.smallSpacing
    /** Off for tiles that aren't albums (podcasts), which handle clicked themselves. */
    property bool opensAlbum: true
    onClicked: if (opensAlbum) applicationWindow().open("AlbumPage", {albumId: album.id, album: album})

    contentItem: ColumnLayout {
        spacing: Kirigami.Units.smallSpacing

        Kirigami.ShadowedRectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: width
            radius: Kirigami.Units.cornerRadius
            color: Kirigami.Theme.alternateBackgroundColor
            shadow.size: Kirigami.Units.smallSpacing
            shadow.color: Qt.rgba(0, 0, 0, 0.2)

            Kirigami.Icon {
                anchors.centerIn: parent
                width: parent.width / 3
                height: width
                source: "media-optical-audio"
                visible: cover.status !== Image.Ready
                color: Kirigami.Theme.disabledTextColor
            }
            Image {
                id: cover
                anchors.fill: parent
                source: tile.album.coverUri
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
                sourceSize.height: height * Screen.devicePixelRatio
            }
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: tile.album.name
            elide: Text.ElideRight
            font.weight: Font.DemiBold
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: tile.subtitle
            elide: Text.ElideRight
            opacity: 0.7
            visible: text.length > 0
        }
    }
}
