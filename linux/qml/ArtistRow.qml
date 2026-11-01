import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD

/** An artist in a list: photo, name and genre; opens the artist. */
KD.SubtitleDelegate {
    id: row

    required property var artist

    width: ListView.view ? ListView.view.width : implicitWidth
    Layout.fillWidth: true
    text: artist.name
    subtitle: artist.genre
    onClicked: applicationWindow().open("ArtistPage", {artistId: artist.id, artist: artist})

    contentItem: RowLayout {
        spacing: Kirigami.Units.largeSpacing
        Kirigami.ShadowedTexture {
            Layout.preferredWidth: Kirigami.Units.iconSizes.large
            Layout.preferredHeight: Kirigami.Units.iconSizes.large
            radius: width / 2
            color: Kirigami.Theme.alternateBackgroundColor
            source: photo.status === Image.Ready ? photo : null

            Image {
                id: photo
                anchors.fill: parent
                visible: false
                source: row.artist.imageUri
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
                sourceSize.height: height * Screen.devicePixelRatio
            }
            Kirigami.Icon {
                anchors.centerIn: parent
                width: parent.width / 2
                height: width
                source: "view-media-artist"
                visible: photo.status !== Image.Ready
                color: Kirigami.Theme.disabledTextColor
            }
        }
        KD.TitleSubtitle {
            Layout.fillWidth: true
            title: row.text
            subtitle: row.subtitle
        }
    }
}
