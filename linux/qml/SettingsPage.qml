import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigamiaddons.formcard as FormCard
import TakeoutFM

/** The server and signing out, what's reported, and About — as KDE apps' settings (FormCard). */
AppPage {
    id: page

    title: qsTr("Settings")

    ColumnLayout {
        spacing: 0

        FormCard.FormHeader { title: qsTr("Account") }
        FormCard.FormCard {
            FormCard.FormTextDelegate {
                text: qsTr("Server")
                description: Core.endpoint
            }
            FormCard.FormDelegateSeparator {}
            FormCard.FormButtonDelegate {
                text: qsTr("Sign Out…")
                icon.name: "system-log-out"
                onClicked: signOut.open()
            }
        }

        FormCard.FormHeader { title: qsTr("Music") }
        FormCard.FormCard {
            FormCard.FormSwitchDelegate {
                text: qsTr("Track activity")
                description: qsTr("Report plays to the Takeout server")
                checked: Core.settings.reportPlays ?? true
                onToggled: Core.call("setSettings", {reportPlays: checked})
            }
        }

        FormCard.FormCard {
            Layout.topMargin: Kirigami.Units.largeSpacing * 2
            FormCard.FormButtonDelegate {
                text: qsTr("About TakeoutFM")
                icon.name: "help-about"
                onClicked: applicationWindow().pageStack.push(Qt.createComponent("org.kde.kirigamiaddons.formcard", "AboutPage"))
            }
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
