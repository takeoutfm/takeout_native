import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD
import TakeoutFM.Core

/** Recently Played on this computer: queues, newest first, resumed where they were left. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Recently Played")

    /** A PlayedQueue as the MusicQueue that plays it again (as the shared toQueue). */
    function toQueue(entry) {
        return {
            title: entry.title,
            creator: entry.creator,
            imageUri: entry.imageUri,
            items: entry.items,
            startIndex: Math.max(0, Math.min(entry.index, entry.items.length - 1)),
            startPositionMs: entry.positionMs,
            location: entry.location,
        }
    }

    actions: [
        Kirigami.Action {
            text: qsTr("Clear")
            icon.name: "edit-clear-history"
            enabled: Core.recentlyPlayed.length > 0
            onTriggered: clear.open()
        }
    ]

    ListView {
        model: Core.recentlyPlayed
        delegate: KD.SubtitleDelegate {
            id: row
            required property var modelData
            width: ListView.view.width
            text: modelData.title
            subtitle: [modelData.creator, new Date(modelData.playedAt).toLocaleString(Qt.locale(), Locale.ShortFormat)]
                .filter(s => s.length > 0).join(" · ")
            onClicked: Player.play(page.toQueue(modelData))

            contentItem: Row {
                spacing: Kirigami.Units.largeSpacing
                Image {
                    width: Kirigami.Units.iconSizes.large
                    height: width
                    source: row.modelData.imageUri || (row.modelData.items[0]?.imageUri ?? "")
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    sourceSize.width: width * Screen.devicePixelRatio
                    sourceSize.height: height * Screen.devicePixelRatio
                }
                KD.TitleSubtitle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - Kirigami.Units.iconSizes.large - parent.spacing
                    title: row.text
                    subtitle: row.subtitle
                }
            }
        }

        Kirigami.PlaceholderMessage {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.gridUnit * 4
            visible: Core.recentlyPlayed.length === 0
            icon.name: "document-open-recent"
            text: qsTr("Nothing played yet")
        }
    }

    Kirigami.PromptDialog {
        id: clear
        title: qsTr("Clear Recently Played?")
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        onAccepted: Core.call("clearRecentlyPlayed")
    }
}
