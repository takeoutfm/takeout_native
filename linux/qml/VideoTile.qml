import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/** A movie or show's poster (an episode's still when [wide]) and title; opens it. */
QQC2.ItemDelegate {
    id: tile

    /** A VideoCard. */
    required property var card
    property bool wide: false

    padding: Kirigami.Units.smallSpacing
    onClicked: applicationWindow().open("VideoPage", {videoId: card.id, card: card})

    contentItem: ColumnLayout {
        spacing: Kirigami.Units.smallSpacing

        Kirigami.ShadowedRectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: tile.wide ? width * 9 / 16 : width * 3 / 2
            radius: Kirigami.Units.cornerRadius
            color: Kirigami.Theme.alternateBackgroundColor
            shadow.size: Kirigami.Units.smallSpacing
            shadow.color: Qt.rgba(0, 0, 0, 0.2)
            clip: true

            Kirigami.Icon {
                anchors.centerIn: parent
                width: parent.width / 3
                height: width
                source: "video-x-generic"
                visible: poster.status !== Image.Ready
                color: Kirigami.Theme.disabledTextColor
            }
            Image {
                id: poster
                anchors.fill: parent
                source: tile.wide ? (tile.card.backdropUri || tile.card.posterUri) : (tile.card.posterUri || tile.card.backdropUri)
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
                sourceSize.height: height * Screen.devicePixelRatio
            }
            // How much has been watched (Continue Watching).
            Rectangle {
                visible: tile.card.progress !== null && tile.card.progress !== undefined
                anchors.left: parent.left
                anchors.bottom: parent.bottom
                height: Kirigami.Units.smallSpacing
                width: parent.width * (tile.card.progress ?? 0)
                color: Kirigami.Theme.highlightColor
            }
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: tile.card.title
            elide: Text.ElideRight
            font.weight: Font.DemiBold
        }
    }
}
