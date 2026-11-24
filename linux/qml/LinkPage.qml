import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigamiaddons.formcard as FormCard
import TakeoutFM
import org.kde.ki18n

/**
 * Link a device (SIGN-11): approve the code another device shows (a TV, a new phone) with this
 * account, as the server's /link page and the phone's and Mac's Link do. The password is never
 * kept: it's cleared once the code is approved, and with the page.
 */
AppPage {
    id: page

    title: KI18n.i18n("Link a Device")

    property bool busy: false
    /** The result to show ("" none), and whether it's good news. */
    property string message: ""
    property bool linked: false

    readonly property bool ready: code.text.trim().length > 0 && user.text.trim().length > 0
        && password.text.length > 0 && !busy

    function link() {
        if (!ready) return
        busy = true
        message = ""
        Core.call("link", {
            code: code.text,
            user: user.text.trim(),
            password: password.text,
            passcode: passcode.text,
        }, (accepted, error) => {
            busy = false
            if (error) {
                linked = false
                message = KI18n.i18n("Can't reach %1. Check your connection.", Core.endpoint)
            } else if (accepted) {
                linked = true
                message = KI18n.i18n("Linked. The other device signs in now.")
                code.text = ""
                password.text = ""
                passcode.text = ""
            } else {
                linked = false
                message = KI18n.i18n("That code, user name or password wasn't accepted.")
            }
        })
    }

    ColumnLayout {
        spacing: 0

        FormCard.FormHeader { title: KI18n.i18n("Approve a Code") }
        FormCard.FormCard {
            FormCard.FormTextDelegate {
                text: KI18n.i18n("Enter the code shown on the TV or other device, with your account to approve it.")
                description: KI18n.i18n("Approved on %1.", Core.endpoint)
            }
            FormCard.FormDelegateSeparator {}
            FormCard.FormTextFieldDelegate {
                id: code
                // the server takes it in any case
                label: KI18n.i18n("Code")
                inputMethodHints: Qt.ImhUppercaseOnly | Qt.ImhNoPredictiveText
                onAccepted: user.forceActiveFocus()
                Component.onCompleted: forceActiveFocus()
            }
            FormCard.FormDelegateSeparator {}
            FormCard.FormTextFieldDelegate {
                id: user
                label: KI18n.i18n("User")
                text: Core.user
                inputMethodHints: Qt.ImhNoAutoUppercase | Qt.ImhNoPredictiveText
                onAccepted: password.forceActiveFocus()
            }
            FormCard.FormDelegateSeparator {}
            FormCard.FormPasswordFieldDelegate {
                id: password
                label: KI18n.i18n("Password")
                onAccepted: passcode.forceActiveFocus()
            }
            FormCard.FormDelegateSeparator {}
            FormCard.FormTextFieldDelegate {
                id: passcode
                label: KI18n.i18n("Passcode")
                placeholderText: KI18n.i18n("Only with two-factor codes")
                inputMethodHints: Qt.ImhDigitsOnly
                onAccepted: page.link()
            }
            FormCard.FormDelegateSeparator {}
            FormCard.FormButtonDelegate {
                text: page.busy ? KI18n.i18n("Linking…") : KI18n.i18n("Link")
                icon.name: "network-connect"
                enabled: page.ready
                onClicked: page.link()
            }
        }

        Kirigami.InlineMessage {
            Layout.fillWidth: true
            Layout.margins: Kirigami.Units.largeSpacing
            visible: page.message.length > 0
            type: page.linked ? Kirigami.MessageType.Positive : Kirigami.MessageType.Error
            text: page.message
        }
    }
}
