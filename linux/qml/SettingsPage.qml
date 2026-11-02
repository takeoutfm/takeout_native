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

    readonly property bool listenBrainzConnected: Core.listenBrainzUser.length > 0
    property bool connecting: false
    /** ListenBrainz turned the last token down. */
    property bool refused: false

    function connectListenBrainz() {
        const value = token.text.trim()
        if (value.length === 0 || connecting) return
        connecting = true
        refused = false
        // The account's name when ListenBrainz accepts the token, null when it doesn't.
        Core.call("connectListenBrainz", {token: value}, (user, error) => {
            connecting = false
            if (user) token.text = ""
            else refused = true
        })
    }

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
                description: KI18n.i18n("Tells your Takeout server what you listen to (half a track, or 4 minutes), for its Activity and stats.")
                checked: Core.settings.reportPlays ?? true
                onToggled: Core.call("setSettings", {reportPlays: checked})
            }
        }

        FormCard.FormHeader { title: KI18n.i18n("ListenBrainz") }
        FormCard.FormCard {
            // Connected: who, whether listens go, and disconnecting.
            FormCard.FormTextDelegate {
                visible: page.listenBrainzConnected
                text: KI18n.i18n("Connected as")
                description: Core.listenBrainzUser
            }
            FormCard.FormDelegateSeparator { visible: page.listenBrainzConnected }
            FormCard.FormSwitchDelegate {
                visible: page.listenBrainzConnected
                text: KI18n.i18n("Submit listens")
                checked: Core.settings.submitListens ?? true
                onToggled: Core.call("setSettings", {submitListens: checked})
            }
            FormCard.FormDelegateSeparator { visible: page.listenBrainzConnected }
            FormCard.FormButtonDelegate {
                visible: page.listenBrainzConnected
                text: KI18n.i18n("Disconnect")
                icon.name: "network-disconnect"
                onClicked: Core.call("disconnectListenBrainz")
            }

            // Not connected: the user token from listenbrainz.org.
            FormCard.FormPasswordFieldDelegate {
                id: token
                visible: !page.listenBrainzConnected
                label: KI18n.i18n("User token")
                enabled: !page.connecting
                onAccepted: page.connectListenBrainz()
            }
            Kirigami.InlineMessage {
                Layout.fillWidth: true
                Layout.margins: Kirigami.Units.largeSpacing
                visible: page.refused && !page.listenBrainzConnected
                type: Kirigami.MessageType.Error
                text: KI18n.i18n("ListenBrainz didn't accept that token.")
            }
            FormCard.FormDelegateSeparator { visible: !page.listenBrainzConnected }
            FormCard.FormButtonDelegate {
                visible: !page.listenBrainzConnected
                text: page.connecting ? KI18n.i18n("Connecting…") : KI18n.i18n("Connect")
                icon.name: "network-connect"
                enabled: token.text.trim().length > 0 && !page.connecting
                onClicked: page.connectListenBrainz()
            }
            FormCard.FormDelegateSeparator { visible: !page.listenBrainzConnected }
            FormCard.FormButtonDelegate {
                visible: !page.listenBrainzConnected
                text: KI18n.i18n("Get your token")
                description: "listenbrainz.org/settings"
                icon.name: "internet-services"
                onClicked: Qt.openUrlExternally("https://listenbrainz.org/settings/")
            }
        }
        FormCard.FormSectionText {
            text: KI18n.i18n("Sends what you play, including songs heard on radio streams, to your ListenBrainz profile.")
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
