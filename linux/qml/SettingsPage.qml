import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigamiaddons.formcard as FormCard
import TakeoutFM
import org.kde.ki18n

/** The server and signing out, what's reported, and About — as KDE apps' settings (FormCard). */
AppPage {
    id: page

    title: KI18n.i18n("Settings")

    ColumnLayout {
        spacing: 0

        FormCard.FormHeader { title: KI18n.i18n("Account") }
        FormCard.FormCard {
            FormCard.FormTextDelegate {
                text: KI18n.i18n("Server")
                description: Core.endpoint
            }
            FormCard.FormDelegateSeparator {}
            FormCard.FormButtonDelegate {
                text: KI18n.i18n("Sign Out…")
                icon.name: "system-log-out"
                onClicked: signOut.open()
            }
        }

        FormCard.FormHeader { title: KI18n.i18n("Music") }
        FormCard.FormCard {
            FormCard.FormSwitchDelegate {
                text: KI18n.i18n("Track activity")
                description: KI18n.i18n("Report plays to the Takeout server")
                checked: Core.settings.reportPlays ?? true
                onToggled: Core.call("setSettings", {reportPlays: checked})
            }
        }

        FormCard.FormCard {
            Layout.topMargin: Kirigami.Units.largeSpacing * 2
            FormCard.FormButtonDelegate {
                text: KI18n.i18n("About TakeoutFM")
                icon.name: "help-about"
                onClicked: applicationWindow().pageStack.push(Qt.createComponent("org.kde.kirigamiaddons.formcard", "AboutPage"))
            }
        }
    }

    Kirigami.PromptDialog {
        id: signOut
        title: KI18n.i18n("Sign out?")
        subtitle: KI18n.i18n("Recently Played on this computer is cleared too.")
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        onAccepted: {
            Player.stop()
            Core.call("signOut")
        }
    }
}
