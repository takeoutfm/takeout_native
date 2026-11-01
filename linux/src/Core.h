#pragma once

#include <QJSValue>
#include <functional>
#include <QObject>
#include <QVariant>
#include <qqmlintegration.h>

// The shared Kotlin core (libtakeout.so) for QML: calls answered on the main thread, and the
// core's shared state (session, library index, settings…) as properties.
class Core : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool ready READ ready NOTIFY sessionChanged)
    Q_PROPERTY(bool signedIn READ signedIn NOTIFY sessionChanged)
    Q_PROPERTY(QString endpoint READ endpoint NOTIFY sessionChanged)
    Q_PROPERTY(QVariantMap library READ library NOTIFY libraryChanged)
    Q_PROPERTY(QVariantMap settings READ settings NOTIFY settingsChanged)
    Q_PROPERTY(QStringList favoriteTracks READ favoriteTracks NOTIFY favoriteTracksChanged)
    Q_PROPERTY(QVariantList recentlyPlayed READ recentlyPlayed NOTIFY recentlyPlayedChanged)

public:
    explicit Core(QObject *parent = nullptr);
    ~Core() override;

    /** Creates the core in [dataDir]; false if it couldn't be. */
    bool start(const QString &dataDir, const QString &userAgent);

    /**
     * Runs shared call [name] with [args]; [callback] gets (result, error) on the main thread:
     * error null on success, else {kind, message, status}. Failures are also announced by
     * [failed], so QML can show them once.
     */
    Q_INVOKABLE void call(const QString &name, const QVariantMap &args = {}, const QJSValue &callback = {});

    using Callback = std::function<void(const QVariant &result, const QVariant &error)>;
    /** [call] for C++: [callback] gets the same (result, error), on the main thread. */
    void call(const QString &name, const QVariantMap &args, Callback callback);

    bool ready() const { return m_ready; }
    bool signedIn() const { return m_signedIn; }
    QString endpoint() const { return m_endpoint; }
    QVariantMap library() const { return m_library; }
    QVariantMap settings() const { return m_settings; }
    QStringList favoriteTracks() const { return m_favoriteTracks; }
    QVariantList recentlyPlayed() const { return m_recentlyPlayed; }

Q_SIGNALS:
    void sessionChanged();
    void libraryChanged();
    void settingsChanged();
    void favoriteTracksChanged();
    void recentlyPlayedChanged();
    void failed(const QString &name, const QVariantMap &error);

private:
    void watch(const char *name);
    void updated(const QByteArray &name, const QVariant &value);
    void run(const QString &name, const QVariantMap &args, const QJSValue &script, Callback native);

    QList<void *> m_watches;
    bool m_ready = false;
    bool m_signedIn = false;
    QString m_endpoint;
    QVariantMap m_library;
    QVariantMap m_settings;
    QStringList m_favoriteTracks;
    QVariantList m_recentlyPlayed;
};
