import QtQuick
import QtQuick.Window
import org.kde.kirigami as Kirigami

/**
 * Cover art, posters and photos: an Image that loads [url] only once it has a size, decoded at
 * its width rounded up to a step. A size that followed the layout exactly reloaded the image
 * (and rescaled it on Qt's worker threads) at every pixel of a window resize, and Qt has
 * crashed doing that; now only crossing a step reloads it.
 */
Image {
    property url url
    readonly property real step: Kirigami.Units.gridUnit * 4

    source: width > 0 && height > 0 ? url : ""
    asynchronous: true
    fillMode: Image.PreserveAspectCrop
    sourceSize.width: Math.max(1, Math.ceil(width / step)) * step * Screen.devicePixelRatio
}
