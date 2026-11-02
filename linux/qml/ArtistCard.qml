import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/** An artist in a row: round photo and name; opens the artist. */
QQC2.ItemDelegate {
    id: card

    required property var artist

    width: Kirigami.Units.gridUnit * 7
    padding: Kirigami.Units.smallSpacing
    onClicked: applicationWindow().open("ArtistPage", {artistId: artist.id, artist: artist})

    contentItem: ColumnLayout {
        spacing: Kirigami.Units.smallSpacing

        // Kirigami's rounded image: the photo cropped to fill the circle, not squeezed into it.
        Kirigami.ShadowedImage {
            id: photo
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: card.availableWidth
            Layout.preferredHeight: card.availableWidth
            radius: width / 2
            color: Kirigami.Theme.alternateBackgroundColor
            source: card.artist.imageUri
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            sourceSize.width: width * Screen.devicePixelRatio
            sourceSize.height: height * Screen.devicePixelRatio
            Kirigami.Icon {
                anchors.centerIn: parent
                width: parent.width / 2
                height: width
                source: "view-media-artist"
                visible: photo.status !== Image.Ready
                color: Kirigami.Theme.disabledTextColor
            }
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: card.artist.name
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
        }
    }
}
