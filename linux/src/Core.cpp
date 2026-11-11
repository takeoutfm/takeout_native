#include "Core.h"

#include <QJSEngine>

namespace {
Core *instance = nullptr;
}

Core::Core(QObject *parent)
    : TakeoutCore(parent)
{
    instance = this;
}

Core *Core::create(QQmlEngine *, QJSEngine *)
{
    QJSEngine::setObjectOwnership(instance, QJSEngine::CppOwnership);
    return instance;
}

void Core::call(const QString &name, const QVariantMap &args, const QJSValue &callback)
{
    if (!callback.isCallable()) return TakeoutCore::call(name, args);
    TakeoutCore::call(name, args, [this, name, callback](const QVariant &value, const QVariant &failure) {
        QJSEngine *engine = qjsEngine(this);
        if (!engine) return;
        // A callback that throws would otherwise fail without a word.
        const QJSValue thrown = QJSValue(callback).call({engine->toScriptValue(value), engine->toScriptValue(failure)});
        if (thrown.isError())
            qWarning().noquote() << QStringLiteral("%1:%2: %3 (in the callback of %4)")
                .arg(thrown.property("fileName").toString(), thrown.property("lineNumber").toString(),
                     thrown.toString(), name);
    });
}

Player *PlayerForeign::create(QQmlEngine *, QJSEngine *)
{
    QJSEngine::setObjectOwnership(instance, QJSEngine::CppOwnership);
    return instance;
}
