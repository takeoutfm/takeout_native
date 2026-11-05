import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * Device-code sign-in: the server gives a code, the user approves it at <server>/link (signed
 * in there, e.g. on their phone), and the app is signed in. No password is typed here. The code
 * shows the time it has left; once it expires it stays, struck out, with a button for a new one.
 */
Kirigami.Page {
    id: page

    title: KI18n.i18n("Sign in")

    property string code: ""
    property string link: ""
    property bool waiting: false
    property bool expired: false
    property double expiresAt: 0 // ms since the epoch
    property int secondsLeft: 0
    property string problem: ""

    function tick() {
        secondsLeft = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))
        if (secondsLeft === 0) expired = true
    }

    Timer {
        interval: 1000
        repeat: true
        running: page.code.length > 0 && !page.expired
        onTriggered: page.tick()
    }

    function start() {
        problem = ""
        code = ""
        expired = false
        waiting = true
        Core.call("requestCode", {host: server.text}, (result, error) => {
            if (error) {
                waiting = false
                problem = error.kind === "network" ? KI18n.i18n("Can't reach %1", server.text) : KI18n.i18n("That server didn't give a code")
                return
            }
            code = result.code
            link = result.link
            expiresAt = Date.now() + result.expiresIn * 1000
            tick()
            const shown = code
            Core.call("awaitCode", {}, (signedIn, error) => {
                if (error?.kind === "cancelled" || code !== shown) return // replaced by a new code
                waiting = false
                if (signedIn) return // Main shows Home.
                if (error) {
                    code = ""
                    problem = KI18n.i18n("Couldn't sign in. Try again.")
                } else {
                    expired = true
                }
            })
        })
    }

    function cancel() {
        Core.call("cancelCode")
        waiting = false
        expired = false
        code = ""
    }

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(parent.width - Kirigami.Units.gridUnit * 2, Kirigami.Units.gridUnit * 24)
        spacing: Kirigami.Units.largeSpacing

        Kirigami.Icon {
            source: "audio-headphones"
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: Kirigami.Units.iconSizes.enormous
            Layout.preferredHeight: Kirigami.Units.iconSizes.enormous
        }
        Kirigami.Heading {
            text: "TakeoutFM"
            Layout.alignment: Qt.AlignHCenter
        }

        Kirigami.FormLayout {
            Layout.fillWidth: true
            visible: page.code.length === 0
            QQC2.TextField {
                id: server
                Kirigami.FormData.label: KI18n.i18n("Server:")
                text: Core.endpoint.length > 0 ? Core.endpoint : "https://takeout.fm"
                enabled: !page.waiting
                inputMethodHints: Qt.ImhUrlCharactersOnly | Qt.ImhNoAutoUppercase
                onAccepted: page.start()
            }
        }
        QQC2.Button {
            Layout.alignment: Qt.AlignHCenter
            visible: page.code.length === 0
            enabled: !page.waiting && server.text.trim().length > 0
            text: KI18n.i18n("Get a code")
            icon.name: "dialog-password"
            onClicked: page.start()
        }

        ColumnLayout {
            visible: page.code.length > 0
            Layout.fillWidth: true
            spacing: Kirigami.Units.largeSpacing

            QQC2.Label {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                textFormat: Text.StyledText
                text: KI18n.i18n("Go to <a href=\"%1\">%1</a>, sign in and enter this code:", page.link)
                onLinkActivated: link => Qt.openUrlExternally(link)
            }
            Kirigami.SelectableLabel {
                Layout.alignment: Qt.AlignHCenter
                text: page.code
                font.pointSize: Kirigami.Theme.defaultFont.pointSize * 3
                font.family: "monospace"
                font.letterSpacing: Kirigami.Units.smallSpacing
                font.strikeout: page.expired
                opacity: page.expired ? 0.38 : 1
            }
            RowLayout {
                Layout.alignment: Qt.AlignHCenter
                visible: !page.expired
                QQC2.BusyIndicator { running: page.waiting }
                QQC2.Label {
                    text: KI18n.i18n("Waiting for approval · expires in %1:%2",
                                     Math.floor(page.secondsLeft / 60),
                                     String(page.secondsLeft % 60).padStart(2, "0"))
                }
            }
            QQC2.Label {
                Layout.alignment: Qt.AlignHCenter
                visible: page.expired
                color: Kirigami.Theme.negativeTextColor
                text: KI18n.i18n("This code has expired.")
            }
            QQC2.Button {
                Layout.alignment: Qt.AlignHCenter
                visible: page.expired
                text: KI18n.i18n("Get a new code")
                icon.name: "view-refresh"
                onClicked: page.start()
            }
            QQC2.Button {
                Layout.alignment: Qt.AlignHCenter
                text: KI18n.i18n("Cancel")
                onClicked: page.cancel()
            }
        }

        Kirigami.InlineMessage {
            Layout.fillWidth: true
            type: Kirigami.MessageType.Error
            text: page.problem
            visible: page.problem.length > 0
        }
    }
}
