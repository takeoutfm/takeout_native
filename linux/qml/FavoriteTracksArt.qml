import QtQuick
import org.kde.kirigami as Kirigami

/** Favorite Tracks' art: a heart on a warm gradient, set apart from the playlists' covers. */
Rectangle {
    radius: Kirigami.Units.cornerRadius
    gradient: Gradient {
        orientation: Gradient.Horizontal
        GradientStop { position: 0; color: "#ff2d55" }
        GradientStop { position: 1; color: "#ff3b30" }
    }
    Kirigami.Icon {
        anchors.centerIn: parent
        width: parent.width / 3
        height: width
        source: "emblem-favorite-symbolic"
        color: "white"
        isMask: true
    }
}
