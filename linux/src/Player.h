#pragma once

#include <QElapsedTimer>
#include <optional>
#include <QObject>
#include <QQmlEngine>
#include <QTimer>
#include <QVariant>

struct mpv_handle;
class Core;

// Music playback on libmpv: the queue as mpv's playlist (gapless, the next item prefetched),
// the media token sent only to the signed-in server, published for QML and MPRIS. As the Mac's
// AudioPlayer, it tells the shared ListenSession what plays (which reports listens), follows
// live streams' ICY titles, keeps Recently Played's place and saves podcast episodes' positions.
class Player : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON
    Q_PROPERTY(QVariantList items READ items NOTIFY itemsChanged)
    Q_PROPERTY(QString queueTitle READ queueTitle NOTIFY itemsChanged)
    Q_PROPERTY(bool hasQueue READ hasQueue NOTIFY itemsChanged)
    Q_PROPERTY(int index READ index NOTIFY currentChanged)
    Q_PROPERTY(QVariantMap current READ current NOTIFY currentChanged)
    Q_PROPERTY(bool playing READ playing NOTIFY playingChanged)
    Q_PROPERTY(double position READ position NOTIFY positionChanged)
    Q_PROPERTY(double duration READ duration NOTIFY durationChanged)
    Q_PROPERTY(bool live READ live NOTIFY durationChanged)
    Q_PROPERTY(QString streamTitle READ streamTitle NOTIFY currentChanged)
    Q_PROPERTY(QString displayTitle READ displayTitle NOTIFY currentChanged)
    Q_PROPERTY(QString displaySubtitle READ displaySubtitle NOTIFY currentChanged)
    Q_PROPERTY(RepeatMode repeatMode READ repeatMode WRITE setRepeatMode NOTIFY repeatModeChanged)

public:
    enum RepeatMode { RepeatOff, RepeatAll, RepeatOne };
    Q_ENUM(RepeatMode)

    explicit Player(Core *core, QObject *parent = nullptr);
    ~Player() override;

    /** QML's Player: the one main() made. */
    static Player *create(QQmlEngine *, QJSEngine *);

    /** Plays [queue] (a MusicQueue) from its start track, or from [start] when it's ≥ 0. */
    Q_INVOKABLE void play(const QVariantMap &queue, bool shuffled = false, int start = -1);
    /** Adds [items] after the current item, or at the end ([last]); plays them if nothing is queued. */
    Q_INVOKABLE void enqueue(const QVariantList &items, bool last);
    Q_INVOKABLE void togglePlay();
    Q_INVOKABLE void resume();
    Q_INVOKABLE void pause();
    Q_INVOKABLE void next();
    Q_INVOKABLE void previous();
    Q_INVOKABLE void skipTo(int index);
    Q_INVOKABLE void seek(double seconds);
    Q_INVOKABLE void cycleRepeat();
    Q_INVOKABLE void stop();

    QVariantList items() const { return m_items; }
    QString queueTitle() const { return m_queueTitle; }
    bool hasQueue() const { return !m_items.isEmpty(); }
    int index() const { return m_index; }
    QVariantMap current() const;
    bool playing() const { return m_playing; }
    double position() const { return m_position; }
    double duration() const { return m_duration; }
    bool live() const { return m_live; }
    QString streamTitle() const { return m_streamTitle; }
    QString displayTitle() const;
    QString displaySubtitle() const;
    RepeatMode repeatMode() const { return m_repeat; }
    void setRepeatMode(RepeatMode mode);

    /** An ICY title as {artist, title}, when it has the form "Artist - Title". */
    static std::optional<std::pair<QString, QString>> splitStreamTitle(const QString &icy);

Q_SIGNALS:
    void itemsChanged();
    void currentChanged();
    void playingChanged();
    void positionChanged();
    void durationChanged();
    void repeatModeChanged();
    /** A jump (not playback moving on), for MPRIS's Seeked. */
    void seeked();

private:
    void onEvents();
    void load(int start, double seconds);
    void loadFile(const QVariantMap &item, const char *flag, int at, double seconds);
    void indexChanged(int index);
    void updatePlaying();
    bool isStream() const;
    void streamTitleChanged(const QString &title);
    void ended(qint64 entryId);
    void tick();
    void saveProgress();
    void saveEpisode();
    qint64 durationMs() const { return qint64(m_duration * 1000); }
    void report(const char *name, const QVariantMap &args);
    /** Off or All, as kept in the settings' repeatAll; a new player and a new queue start with it. */
    RepeatMode savedRepeat() const;
    void restoreRepeat();

    Core *m_core;
    mpv_handle *m_mpv = nullptr;
    QTimer m_ticker;

    QVariantList m_items;
    QString m_queueTitle;
    int m_index = -1;
    /** Bumped by each play(), so a load waiting on the media token can tell it's stale. */
    int m_generation = 0;
    /** "Authorization: Bearer …" for the signed-in server's streams. */
    QString m_authorization;
    QString m_historyId;

    bool m_paused = false;
    bool m_idle = true;
    bool m_playing = false;
    bool m_loaded = false;
    double m_position = 0;
    double m_duration = 0;
    bool m_live = false;
    QString m_streamTitle;
    RepeatMode m_repeat = RepeatOff;
    /** The saved mode has been applied (settings arrive from the core after start). */
    bool m_repeatRestored = false;

    QElapsedTimer m_savedAt;
    QElapsedTimer m_episodeSavedAt;
};
