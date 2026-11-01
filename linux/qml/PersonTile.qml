import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/** Someone in the cast: round photo, name and role; opens their page. */
QQC2.ItemDelegate {
    id: tile

    /** A CastCredit. */
    required property var credit

    width: Kirigami.Units.gridUnit * 7
    padding: Kirigami.Units.smallSpacing
    onClicked: applicationWindow().open("PersonPage", {personId: credit.personId, name: credit.name})

    contentItem: ColumnLayout {
        spacing: Kirigami.Units.smallSpacing

        Kirigami.ShadowedTexture {
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: Kirigami.Units.gridUnit * 5
            Layout.preferredHeight: Kirigami.Units.gridUnit * 5
            radius: width / 2
            color: Kirigami.Theme.alternateBackgroundColor
            source: photo.status === Image.Ready ? photo : null

            Image {
                id: photo
                anchors.fill: parent
                visible: false
                source: tile.credit.avatarUri
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
                sourceSize.height: height * Screen.devicePixelRatio
            }
            Kirigami.Icon {
                anchors.centerIn: parent
                width: parent.width / 2
                height: width
                source: "user-identity"
                visible: photo.status !== Image.Ready
                color: Kirigami.Theme.disabledTextColor
            }
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: tile.credit.name
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
            font.weight: Font.DemiBold
        }
        QQC2.Label {
            Layout.fillWidth: true
            text: tile.credit.role
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
            opacity: 0.7
            visible: text.length > 0
        }
    }
}
