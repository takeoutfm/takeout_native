import QtQuick
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/** Asks before deleting [playlist] ({id, name}): it goes from the server, for every app. */
Kirigami.PromptDialog {
    id: dialog

    property var playlist: null
    /** After the server has deleted it (a playlist's own page closes). */
    signal deleted()

    title: KI18n.i18n("Delete “%1”?", (playlist?.name ?? "").trim())
    subtitle: KI18n.i18n("The playlist is deleted on the server, for every app. Its tracks stay in the library.")
    standardButtons: Kirigami.Dialog.Cancel
    customFooterActions: [
        Kirigami.Action {
            text: KI18n.i18n("Delete")
            icon.name: "edit-delete"
            onTriggered: {
                const p = dialog.playlist
                dialog.close()
                Core.call("deletePlaylist", {playlistId: p.id}, (r, error) => {
                    if (error) applicationWindow().showPassiveNotification(KI18n.i18n("Couldn't delete %1", p.name))
                    else dialog.deleted()
                })
            }
        }
    ]
}
