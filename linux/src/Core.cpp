#include "Core.h"

#include "SecretStore.h"
#include "takeout.h"

#include <QCoreApplication>
#include <QJSEngine>
#include <QJsonArray>
#include <QJsonDocument>
#include <QPointer>

namespace {
/** JSON of any kind (object, list, string, number…) as a QVariant. */
QVariant parse(const char *json)
{
    if (!json) return {};
    return QJsonDocument::fromJson(QByteArray("[") + json + "]").array().at(0).toVariant();
}

struct PendingCall {
    QPointer<Core> core;
    QString name;
    QJSValue callback;
};

struct Watcher {
    QPointer<Core> core;
    QByteArray name;
};
}

Core::Core(QObject *parent)
    : QObject(parent)
{
}

Core::~Core()
{
    for (void *handle : std::as_const(m_watches)) takeout_unwatch(handle);
}

bool Core::start(const QString &dataDir, const QString &userAgent)
{
    const std::string secrets = SecretStore::load();
    const int status = takeout_init(
        dataDir.toUtf8().constData(), userAgent.toUtf8().constData(),
        secrets.empty() ? nullptr : secrets.c_str(),
        [](void *, const char *json) { SecretStore::save(json); }, nullptr);
    if (status != 0) return false;
    for (const char *name : {"session", "library", "settings", "favoriteTracks", "recentlyPlayed"}) watch(name);
    return true;
}

void Core::watch(const char *name)
{
    // Lives as long as the app; the watch is stopped in the destructor.
    auto *watcher = new Watcher{this, name};
    void *handle = takeout_watch(name, [](void *ctx, const char *json) {
        auto *w = static_cast<Watcher *>(ctx);
        QVariant value = parse(json);
        QMetaObject::invokeMethod(w->core, [w, value] {
            if (w->core) w->core->updated(w->name, value);
        }, Qt::QueuedConnection);
    }, watcher);
    if (handle) m_watches << handle;
}

void Core::updated(const QByteArray &name, const QVariant &value)
{
    if (name == "session") {
        const QVariantMap session = value.toMap();
        m_ready = true;
        m_signedIn = session.value("signedIn").toBool();
        m_endpoint = session.value("endpoint").toString();
        Q_EMIT sessionChanged();
    } else if (name == "library") {
        m_library = value.toMap();
        Q_EMIT libraryChanged();
    } else if (name == "settings") {
        m_settings = value.toMap();
        Q_EMIT settingsChanged();
    } else if (name == "favoriteTracks") {
        m_favoriteTracks = value.toStringList();
        Q_EMIT favoriteTracksChanged();
    } else if (name == "recentlyPlayed") {
        m_recentlyPlayed = value.toList();
        Q_EMIT recentlyPlayedChanged();
    }
}

void Core::call(const QString &name, const QVariantMap &args, const QJSValue &callback)
{
    auto *pending = new PendingCall{this, name, callback};
    const QByteArray json = QJsonDocument::fromVariant(args).toJson(QJsonDocument::Compact);
    takeout_call(name.toUtf8().constData(), json.constData(), [](void *ctx, const char *result, const char *error) {
        auto *p = static_cast<PendingCall *>(ctx);
        QVariant value = parse(result);
        QVariant failure = parse(error);
        QObject *receiver = p->core ? static_cast<QObject *>(p->core.data()) : QCoreApplication::instance();
        QMetaObject::invokeMethod(receiver, [p, value, failure] {
            if (p->core) {
                QJSEngine *engine = qjsEngine(p->core.data());
                if (engine && p->callback.isCallable())
                    p->callback.call({engine->toScriptValue(value), engine->toScriptValue(failure)});
                if (failure.isValid()) Q_EMIT p->core->failed(p->name, failure.toMap());
            }
            delete p;
        }, Qt::QueuedConnection);
    }, pending);
}
