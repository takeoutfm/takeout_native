import QtQuick
import QtQuick.Layouts
import org.kde.kirigami as Kirigami

/**
 * A playlist's art, as on the Mac and the phone: four album covers in a square, or the first
 * alone when it has fewer; a playlist icon with none (empty, or while they load).
 */
Kirigami.ShadowedRectangle {
    id: mosaic

    property var covers: []

    radius: Kirigami.Units.cornerRadius
    color: Kirigami.Theme.alternateBackgroundColor
    shadow.size: Kirigami.Units.smallSpacing
    shadow.color: Qt.rgba(0, 0, 0, 0.2)

    Kirigami.Icon {
        anchors.centerIn: parent
        width: parent.width / 3
        height: width
        source: "view-media-playlist"
        visible: mosaic.covers.length === 0
        color: Kirigami.Theme.disabledTextColor
    }
    // One cover fills the square.
    Art {
        anchors.fill: parent
        visible: mosaic.covers.length > 0 && mosaic.covers.length < 4
        url: visible ? mosaic.covers[0] : ""
        fillMode: Image.PreserveAspectCrop
    }
    // Four in a square.
    Item {
        anchors.fill: parent
        visible: mosaic.covers.length >= 4
        GridLayout {
            anchors.fill: parent
            columns: 2
            rowSpacing: 0
            columnSpacing: 0
            Repeater {
                model: mosaic.covers.length >= 4 ? mosaic.covers.slice(0, 4) : []
                Art {
                    required property string modelData
                    // Equal quarters, whatever size each image is.
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Layout.preferredWidth: 1
                    Layout.preferredHeight: 1
                    url: modelData
                    fillMode: Image.PreserveAspectCrop
                }
            }
        }
    }
}
