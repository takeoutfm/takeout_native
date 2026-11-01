import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/** A track in a list: its number (or cover), title and a second line. */
QQC2.ItemDelegate {
    id: row

    property string number: ""
    property string title
    property string subtitle: ""
    property string imageUri: ""
    property bool favorite: false

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
        Kirigami.Icon {
            visible: row.favorite
            source: "emblem-favorite"
            Layout.preferredWidth: Kirigami.Units.iconSizes.small
            Layout.preferredHeight: Kirigami.Units.iconSizes.small
        }
    }
}
