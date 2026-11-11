#pragma once

#include <functional>
#include <QObject>
#include <QStringList>
#include <QVariant>

// The shared Kotlin core (libtakeout.so) for the Linux clients: calls answered on the main
// thread, and the core's shared state (session, library index, settings…) as properties.
// Qt Core only: the KDE app's QML `Core` derives from it, and tofm uses it as it is.
class TakeoutCore : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool ready READ ready NOTIFY sessionChanged)
    Q_PROPERTY(bool signedIn READ signedIn NOTIFY sessionChanged)
    Q_PROPERTY(QString endpoint READ endpoint NOTIFY sessionChanged)
    /** Who's signed in to the server (/api/me); empty until it says. */
    Q_PROPERTY(QString user READ user NOTIFY sessionChanged)
    Q_PROPERTY(QVariantMap library READ library NOTIFY libraryChanged)
    Q_PROPERTY(QVariantMap settings READ settings NOTIFY settingsChanged)
    Q_PROPERTY(QStringList favoriteTracks READ favoriteTracks NOTIFY favoriteTracksChanged)
    Q_PROPERTY(QVariantList recentlyPlayed READ recentlyPlayed NOTIFY recentlyPlayedChanged)
    /** The connected ListenBrainz user's name; "" when none is connected. */
    Q_PROPERTY(QString listenBrainzUser READ listenBrainzUser NOTIFY listenBrainzChanged)
    /** Downloads ({entry, state, fraction, path}), and the same keyed by ETag. */
    Q_PROPERTY(QVariantList downloads READ downloads NOTIFY downloadsChanged)
    Q_PROPERTY(QVariantMap downloadsByEtag READ downloadsByEtag NOTIFY downloadsChanged)

public:
    explicit TakeoutCore(QObject *parent = nullptr);
    ~TakeoutCore() override;

    /**
     * Creates the core (once a process) in [dataDir], its secrets kept in the Secret Service
     * item for [secretsApp] ("takeoutfm", "tofm"); false if it couldn't be.
     */
    bool start(const QString &dataDir, const QString &userAgent, const QString &secretsApp);

    using Callback = std::function<void(const QVariant &result, const QVariant &error)>;
    /**
     * Runs shared call [name] with [args]; [callback] gets (result, error) on the main thread:
     * error null on success, else {kind, message, status}. Failures are also announced by
     * [failed], so a UI can show them once.
     */
    void call(const QString &name, const QVariantMap &args = {}, Callback callback = {});

    bool ready() const { return m_ready; }
    bool signedIn() const { return m_signedIn; }
    QString endpoint() const { return m_endpoint; }
    QString user() const { return m_user; }
    QVariantMap library() const { return m_library; }
    QVariantMap settings() const { return m_settings; }
    QStringList favoriteTracks() const { return m_favoriteTracks; }
    QVariantList recentlyPlayed() const { return m_recentlyPlayed; }
    QString listenBrainzUser() const { return m_listenBrainzUser; }
    QVariantList downloads() const { return m_downloads; }
    QVariantMap downloadsByEtag() const { return m_downloadsByEtag; }
    /** The downloaded file for [etag], or "" when it isn't wholly here. */
    Q_INVOKABLE QString localFile(const QString &etag) const;

Q_SIGNALS:
    void sessionChanged();
    void libraryChanged();
    void settingsChanged();
    void favoriteTracksChanged();
    void recentlyPlayedChanged();
    void listenBrainzChanged();
    void downloadsChanged();
    void failed(const QString &name, const QVariantMap &error);

private:
    void watch(const char *name);
    void updated(const QByteArray &name, const QVariant &value);

    QList<void *> m_watches;
    bool m_ready = false;
    bool m_signedIn = false;
    QString m_endpoint;
    QString m_user;
    QVariantMap m_library;
    QVariantMap m_settings;
    QStringList m_favoriteTracks;
    QVariantList m_recentlyPlayed;
    QString m_listenBrainzUser;
    QVariantList m_downloads;
    QVariantMap m_downloadsByEtag;
};
