import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/** A radio station: its image (streams have one) or an icon, name and description. */
QQC2.ItemDelegate {
    id: tile

    required property var station

    padding: Kirigami.Units.smallSpacing
    onClicked: applicationWindow().playCall("stationQueue", {stationId: station.id})

    contentItem: ColumnLayout {
        spacing: Kirigami.Units.smallSpacing

        Kirigami.ShadowedRectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: width
            radius: Kirigami.Units.cornerRadius
            color: Kirigami.Theme.alternateBackgroundColor

            Kirigami.Icon {
                anchors.centerIn: parent
                width: parent.width / 3
                height: width
                source: "radio"
                visible: image.status !== Image.Ready
                color: Kirigami.Theme.disabledTextColor
            }
            Image {
                id: image
                anchors.fill: parent
                anchors.margins: Kirigami.Units.smallSpacing
                source: tile.station.imageUri
                fillMode: Image.PreserveAspectFit
                asynchronous: true
            }
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: tile.station.name
            elide: Text.ElideRight
            font.weight: Font.DemiBold
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: tile.station.description
            elide: Text.ElideRight
            opacity: 0.7
            visible: text.length > 0
        }
    }
}
