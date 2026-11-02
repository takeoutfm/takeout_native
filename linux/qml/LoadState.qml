import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.ki18n

/** A spinner while [loading], or [error] (a failed call's) with Retry. */
Item {
    id: state

    property bool loading: false
    property var error: null
    signal retry()

    visible: loading || !!error
    implicitWidth: Kirigami.Units.gridUnit * 20
    implicitHeight: Kirigami.Units.gridUnit * 10

    QQC2.BusyIndicator {
        anchors.centerIn: parent
        running: state.loading
        visible: running
    }

    Kirigami.PlaceholderMessage {
        anchors.centerIn: parent
        width: parent.width - Kirigami.Units.gridUnit * 2
        visible: !state.loading && !!state.error
        icon.name: state.error?.kind === "network" ? "network-disconnect" : "dialog-warning"
        text: state.error?.kind === "network" ? KI18n.i18n("Can't reach the server")
            : state.error?.status === 404 ? KI18n.i18n("Not found")
            : KI18n.i18n("Something went wrong")
        explanation: state.error?.kind === "network" ? "" : (state.error?.message ?? "")
        helpfulAction: Kirigami.Action {
            text: KI18n.i18n("Retry")
            icon.name: "view-refresh"
            onTriggered: state.retry()
        }
    }
}
