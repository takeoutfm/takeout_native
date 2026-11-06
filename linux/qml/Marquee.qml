import QtQuick
import org.kde.kirigami as Kirigami

/**
 * One line of heading text that, when it's too wide, waits a moment, then slides sideways with a
 * second copy following, and repeats (Now Playing's title, as Android's basicMarquee). Text that
 * fits sits still, aligned as [horizontalAlignment] says. Restarts from the beginning when the
 * text changes, and only moves while it's visible.
 */
Item {
    id: root

    property string text
    property int level: 1
    property int horizontalAlignment: Text.AlignLeft

    readonly property bool overflowing: first.implicitWidth > width
    readonly property real gap: Kirigami.Units.gridUnit * 3
    /** Pixels a second: about Compose's default speed. */
    readonly property real speed: Kirigami.Units.gridUnit * 2

    implicitHeight: first.implicitHeight
    clip: true

    Row {
        id: strip
        spacing: root.gap

        Kirigami.Heading {
            id: first
            level: root.level
            text: root.text
            wrapMode: Text.NoWrap
            width: root.overflowing ? implicitWidth : root.width
            horizontalAlignment: root.overflowing ? Text.AlignLeft : root.horizontalAlignment
        }
        Kirigami.Heading {
            level: root.level
            text: root.text
            wrapMode: Text.NoWrap
            visible: root.overflowing
        }
    }

    SequentialAnimation {
        id: slide
        running: root.overflowing && root.visible
        loops: Animation.Infinite
        PropertyAction { target: strip; property: "x"; value: 0 }
        PauseAnimation { duration: 1500 }
        // From one copy to the next, where it looks the same as the start.
        NumberAnimation {
            target: strip
            property: "x"
            from: 0
            to: -(first.implicitWidth + root.gap)
            duration: (first.implicitWidth + root.gap) / root.speed * 1000
        }
        onRunningChanged: if (!running) strip.x = 0
    }

    // A new title (or width) starts over: wait, then slide.
    onTextChanged: if (slide.running) slide.restart()
    onWidthChanged: if (slide.running) slide.restart()
}
