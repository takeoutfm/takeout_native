import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** The server, what's reported, and signing out. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Settings")

    Kirigami.FormLayout {
        QQC2.Label {
            Kirigami.FormData.label: qsTr("Server:")
            text: Core.endpoint
        }
        QQC2.Button {
            text: qsTr("Sign out…")
            icon.name: "system-log-out"
            onClicked: signOut.open()
        }

        Kirigami.Separator { Kirigami.FormData.isSection: true; Kirigami.FormData.label: qsTr("Music") }
        QQC2.Switch {
            Kirigami.FormData.label: qsTr("Track activity:")
            text: qsTr("Report plays to the Takeout server")
            checked: Core.settings.reportPlays ?? true
            onToggled: Core.call("setSettings", {reportPlays: checked})
        }

        Kirigami.Separator { Kirigami.FormData.isSection: true; Kirigami.FormData.label: qsTr("About") }
        QQC2.Label {
            Kirigami.FormData.label: qsTr("Version:")
            text: Qt.application.version
        }
    }

    Kirigami.PromptDialog {
        id: signOut
        title: qsTr("Sign out?")
        subtitle: qsTr("Recently Played on this computer is cleared too.")
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        onAccepted: {
            Player.stop()
            Core.call("signOut")
        }
    }
}
