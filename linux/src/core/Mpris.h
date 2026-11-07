#pragma once

#include <QDBusAbstractAdaptor>
#include <QDBusObjectPath>
#include <QObject>
#include <QVariantMap>

class Player;

/** Who the player is on D-Bus: org.mpris.MediaPlayer2.<busName>, and what it tells controllers. */
struct MprisIdentity {
    QString busName;      // "takeoutfm", "tofm"
    QString identity;     // "TakeoutFM"
    QString desktopEntry; // "com.takeoutfm.linux"; "" for none
    bool canRaise = true; // a window to show (Raise → raiseRequested)
};

// MPRIS on the session bus: Plasma's media controls, the lock screen, media keys, KDE Connect
// and playerctl see and drive the music player. ♥ stays in the app: MPRIS has no favorite
// action.
class Mpris : public QObject
{
    Q_OBJECT

public:
    Mpris(Player *player, const MprisIdentity &identity, QObject *parent = nullptr);
    ~Mpris() override;

    Player *player() const { return m_player; }
    const MprisIdentity &identity() const { return m_identity; }
    QVariantMap metadata() const;
    QString playbackStatus() const;

Q_SIGNALS:
    /** Raise() from a media controller: show the window. */
    void raiseRequested();

private:
    void changed(const QVariantMap &properties);

    Player *m_player;
    MprisIdentity m_identity;
    QString m_artUrl;
};

/** org.mpris.MediaPlayer2 */
class MprisRoot : public QDBusAbstractAdaptor
{
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "org.mpris.MediaPlayer2")
    Q_PROPERTY(bool CanQuit READ yes)
    Q_PROPERTY(bool CanRaise READ canRaise)
    Q_PROPERTY(bool HasTrackList READ no)
    Q_PROPERTY(QString Identity READ identity)
    Q_PROPERTY(QString DesktopEntry READ desktopEntry)
    Q_PROPERTY(QStringList SupportedUriSchemes READ none)
    Q_PROPERTY(QStringList SupportedMimeTypes READ none)

public:
    explicit MprisRoot(Mpris *mpris);
    bool yes() const { return true; }
    bool no() const { return false; }
    bool canRaise() const { return m_mpris->identity().canRaise; }
    QString identity() const { return m_mpris->identity().identity; }
    QString desktopEntry() const { return m_mpris->identity().desktopEntry; }
    QStringList none() const { return {}; }

public Q_SLOTS:
    void Raise();
    void Quit();

private:
    Mpris *m_mpris;
};

/** org.mpris.MediaPlayer2.Player */
class MprisPlayer : public QDBusAbstractAdaptor
{
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "org.mpris.MediaPlayer2.Player")
    Q_PROPERTY(QString PlaybackStatus READ playbackStatus)
    Q_PROPERTY(QString LoopStatus READ loopStatus WRITE setLoopStatus)
    Q_PROPERTY(double Rate READ one WRITE setRate)
    Q_PROPERTY(bool Shuffle READ no WRITE setShuffle)
    Q_PROPERTY(QVariantMap Metadata READ metadata)
    Q_PROPERTY(double Volume READ one WRITE setVolume)
    Q_PROPERTY(qlonglong Position READ position)
    Q_PROPERTY(double MinimumRate READ one)
    Q_PROPERTY(double MaximumRate READ one)
    Q_PROPERTY(bool CanGoNext READ canGoNext)
    Q_PROPERTY(bool CanGoPrevious READ hasQueue)
    Q_PROPERTY(bool CanPlay READ hasQueue)
    Q_PROPERTY(bool CanPause READ hasQueue)
    Q_PROPERTY(bool CanSeek READ canSeek)
    Q_PROPERTY(bool CanControl READ yes)

public:
    explicit MprisPlayer(Mpris *mpris);
    QString playbackStatus() const { return m_mpris->playbackStatus(); }
    QString loopStatus() const;
    void setLoopStatus(const QString &status);
    double one() const { return 1.0; }
    void setRate(double) {}
    bool no() const { return false; }
    bool yes() const { return true; }
    void setShuffle(bool) {}
    void setVolume(double) {}
    QVariantMap metadata() const { return m_mpris->metadata(); }
    qlonglong position() const;
    bool canGoNext() const;
    bool hasQueue() const;
    bool canSeek() const;

public Q_SLOTS:
    void Next();
    void Previous();
    void Pause();
    void PlayPause();
    void Stop();
    void Play();
    void Seek(qlonglong offset);
    void SetPosition(const QDBusObjectPath &trackId, qlonglong position);
    void OpenUri(const QString &) {}

Q_SIGNALS:
    void Seeked(qlonglong position);

private:
    Mpris *m_mpris;
};
