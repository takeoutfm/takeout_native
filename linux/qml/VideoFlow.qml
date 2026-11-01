import QtQuick
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/** A titled, wrapping set of video tiles; hidden when [cards] is empty. */
ColumnLayout {
    id: flow

    property string title
    property var cards: []
    /** Stills (episodes) instead of posters. */
    property bool wide: false
    /** At most this many tiles (0: all). */
    property int limit: 0

    visible: cards.length > 0
    Layout.fillWidth: true
    spacing: Kirigami.Units.smallSpacing

    SectionHeading { text: flow.title; visible: flow.title.length > 0 }

    Flow {
        id: tiles
        Layout.fillWidth: true
        readonly property int columns: Math.max(1, Math.floor(flow.width / (Kirigami.Units.gridUnit * (flow.wide ? 13 : 8))))

        Repeater {
            model: flow.limit > 0 ? flow.cards.slice(0, flow.limit) : flow.cards
            VideoTile {
                required property var modelData
                card: modelData
                wide: flow.wide
                width: flow.width / tiles.columns
            }
        }
    }
}
