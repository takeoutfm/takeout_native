#pragma once

#include "core/Player.h"
#include "core/TakeoutCore.h"

#include <QJSValue>
#include <QQmlEngine>

// QML's Core: the shared core (TakeoutCore) with calls whose callback is a JavaScript function.
class Core : public TakeoutCore
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON

public:
    // No default parent: a default-constructible singleton would be created by QML itself,
    // a second core, instead of coming from create().
    explicit Core(QObject *parent);

    /** QML's Core: the one main() made (the app has one core, created before QML loads). */
    static Core *create(QQmlEngine *, QJSEngine *);

    /** TakeoutCore::call for QML: [callback] gets (result, error) on the main thread. */
    Q_INVOKABLE void call(const QString &name, const QVariantMap &args = {}, const QJSValue &callback = {});
};

// QML's Player: the one main() made (core/Player, which has no QML of its own).
struct PlayerForeign {
    Q_GADGET
    QML_FOREIGN(Player)
    QML_NAMED_ELEMENT(Player)
    QML_SINGLETON

public:
    inline static Player *instance = nullptr;
    static Player *create(QQmlEngine *, QJSEngine *);
};
