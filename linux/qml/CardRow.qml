import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/**
 * A titled, horizontally scrolling row of cards, as the Mac's rows (albums, artists, stations,
 * videos); hidden when [model] is empty. [delegate] is the card (with `required property var
 * modelData` and its own width); the row is as tall as its tallest card.
 */
ColumnLayout {
    id: row

    property string title
    property var model: []
    property Component delegate

    visible: model.length > 0
    Layout.fillWidth: true
    spacing: Kirigami.Units.smallSpacing

    SectionHeading { text: row.title; visible: row.title.length > 0 }

    ListView {
        id: list
        Layout.fillWidth: true
        Layout.preferredHeight: contentItem.childrenRect.height + (scrollBar.visible ? scrollBar.height : 0)
        orientation: ListView.Horizontal
        spacing: Kirigami.Units.smallSpacing
        clip: true
        model: row.model
        delegate: row.delegate
        QQC2.ScrollBar.horizontal: QQC2.ScrollBar {
            id: scrollBar
            policy: list.contentWidth > list.width ? QQC2.ScrollBar.AlwaysOn : QQC2.ScrollBar.AlwaysOff
        }
    }
}
