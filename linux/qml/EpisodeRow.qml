import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.ki18n

/** An episode in a season: its still, number and name, and description; opens it. */
QQC2.ItemDelegate {
    id: row

    /** An EpisodeInfo. */
    required property var episode

    Layout.fillWidth: true
    onClicked: if (!highlighted) applicationWindow().open("VideoPage", {videoId: episode.id})

    contentItem: RowLayout {
        spacing: Kirigami.Units.largeSpacing

        Image {
            Layout.preferredWidth: Kirigami.Units.gridUnit * 8
            Layout.preferredHeight: Kirigami.Units.gridUnit * 4.5
            Layout.alignment: Qt.AlignTop
            source: row.episode.stillUri
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            sourceSize.width: width * Screen.devicePixelRatio
            sourceSize.height: height * Screen.devicePixelRatio
        }
        ColumnLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignTop
            spacing: 0
            QQC2.Label {
                Layout.fillWidth: true
                text: `${row.episode.episode}. ${row.episode.name}`
                elide: Text.ElideRight
                font.weight: Font.DemiBold
            }
            QQC2.Label {
                Layout.fillWidth: true
                text: row.episode.description
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
                opacity: 0.7
            }
            QQC2.Label {
                visible: row.episode.runtimeMinutes > 0
                text: KI18n.i18n("%1 min", row.episode.runtimeMinutes)
                opacity: 0.6
                font: Kirigami.Theme.smallFont
            }
        }
    }
}
