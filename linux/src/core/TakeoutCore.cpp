#include "TakeoutCore.h"

#include "HttpHost.h"
#include "SecretStore.h"
#include "takeout.h"

#include <QCoreApplication>
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
    QPointer<TakeoutCore> core;
    QString name;
    TakeoutCore::Callback callback;
};

struct Watcher {
    QPointer<TakeoutCore> core;
    QByteArray name;
};
}

TakeoutCore::TakeoutCore(QObject *parent)
    : QObject(parent)
{
}

TakeoutCore::~TakeoutCore()
{
    for (void *handle : std::as_const(m_watches)) takeout_unwatch(handle);
}

bool TakeoutCore::start(const QString &dataDir, const QString &userAgent, const QString &secretsApp)
{
    // Read by the core's threads for every save, for as long as the process runs.
    static std::string app;
    app = secretsApp.toStdString();
    const std::string secrets = SecretStore::load(app);
    // The core's HTTP goes through Qt Network (PLAN.md L8).
    HttpHost::install();
    const int status = takeout_init(
        dataDir.toUtf8().constData(), userAgent.toUtf8().constData(),
        secrets.empty() ? nullptr : secrets.c_str(),
        [](void *, const char *json) { SecretStore::save(app, json); }, nullptr);
    if (status != 0) return false;
    for (const char *name : {"session", "library", "settings", "favoriteTracks", "recentlyPlayed", "downloads", "listenBrainz"}) watch(name);
    return true;
}

void TakeoutCore::watch(const char *name)
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

void TakeoutCore::updated(const QByteArray &name, const QVariant &value)
{
    if (name == "session") {
        const QVariantMap session = value.toMap();
        m_ready = true;
        m_signedIn = session.value("signedIn").toBool();
        m_endpoint = session.value("endpoint").toString();
        m_user = session.value("user").toString();
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
    } else if (name == "listenBrainz") {
        m_listenBrainzUser = value.toMap().value("name").toString();
        Q_EMIT listenBrainzChanged();
    } else if (name == "downloads") {
        m_downloads = value.toList();
        m_downloadsByEtag.clear();
        for (const QVariant &download : std::as_const(m_downloads))
            m_downloadsByEtag.insert(download.toMap().value("entry").toMap().value("etag").toString(), download);
        Q_EMIT downloadsChanged();
    }
}

QString TakeoutCore::localFile(const QString &etag) const
{
    if (etag.isEmpty()) return {};
    const QVariantMap download = m_downloadsByEtag.value(etag).toMap();
    return download.value("state").toString() == "Done" ? download.value("path").toString() : QString();
}

void TakeoutCore::call(const QString &name, const QVariantMap &args, Callback callback)
{
    auto *pending = new PendingCall{this, name, std::move(callback)};
    const QByteArray json = QJsonDocument::fromVariant(args).toJson(QJsonDocument::Compact);
    takeout_call(name.toUtf8().constData(), json.constData(), [](void *ctx, const char *result, const char *error) {
        auto *p = static_cast<PendingCall *>(ctx);
        QVariant value = parse(result);
        QVariant failure = parse(error);
        QObject *receiver = p->core ? static_cast<QObject *>(p->core.data()) : QCoreApplication::instance();
        QMetaObject::invokeMethod(receiver, [p, value, failure] {
            if (p->core) {
                if (p->callback) p->callback(value, failure);
                if (failure.isValid()) Q_EMIT p->core->failed(p->name, failure.toMap());
            }
            delete p;
        }, Qt::QueuedConnection);
    }, pending);
}
