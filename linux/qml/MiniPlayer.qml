import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** The footer while something is queued: cover, what's playing, previous / play / next. */
QQC2.ToolBar {
    id: bar

    signal open()

    position: QQC2.ToolBar.Footer
    padding: 0
    // Hidden, it takes no room.
    height: visible ? implicitHeight : 0
    implicitHeight: content.implicitHeight

    ColumnLayout {
        id: content
        anchors.fill: parent
        spacing: 0

        // How far into the track (nothing for a live stream).
        QQC2.ProgressBar {
            Layout.fillWidth: true
            Layout.preferredHeight: Kirigami.Units.smallSpacing / 2
            visible: !Player.live
            from: 0
            to: Math.max(Player.duration, 1)
            value: Player.position
            background: null
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: Kirigami.Units.smallSpacing
            spacing: Kirigami.Units.largeSpacing

            // What's playing; opens Now Playing.
            RowLayout {
                Layout.fillWidth: true
                spacing: Kirigami.Units.largeSpacing

                TapHandler { onTapped: bar.open() }
                HoverHandler { cursorShape: Qt.PointingHandCursor }

                Image {
                    Layout.preferredWidth: Kirigami.Units.iconSizes.large
                    Layout.preferredHeight: Kirigami.Units.iconSizes.large
                    source: Player.current.imageUri ?? ""
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    sourceSize.width: width * Screen.devicePixelRatio
                    sourceSize.height: height * Screen.devicePixelRatio
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 0
                    QQC2.Label {
                        Layout.fillWidth: true
                        text: Player.displayTitle
                        elide: Text.ElideRight
                        font.weight: Font.DemiBold
                    }
                    QQC2.Label {
                        Layout.fillWidth: true
                        text: Player.displaySubtitle
                        elide: Text.ElideRight
                        opacity: 0.7
                    }
                }
            }

            QQC2.ToolButton {
                icon.name: "media-skip-backward"
                display: QQC2.AbstractButton.IconOnly
                text: qsTr("Previous")
                onClicked: Player.previous()
            }
            QQC2.ToolButton {
                icon.name: Player.playing ? "media-playback-pause" : "media-playback-start"
                icon.width: Kirigami.Units.iconSizes.medium
                icon.height: Kirigami.Units.iconSizes.medium
                display: QQC2.AbstractButton.IconOnly
                text: Player.playing ? qsTr("Pause") : qsTr("Play")
                onClicked: Player.togglePlay()
            }
            QQC2.ToolButton {
                icon.name: "media-skip-forward"
                display: QQC2.AbstractButton.IconOnly
                text: qsTr("Next")
                enabled: Player.index + 1 < Player.items.length
                onClicked: Player.next()
            }
        }
    }
}
