#pragma once

#include <QQuickFramebufferObject>
#include <QVariant>
#include <qqmlintegration.h>

struct mpv_handle;
struct mpv_render_context;

// A video drawn by Qt Quick: its own mpv (hardware decoding where it's safe) rendering into
// this item's framebuffer object. Every embedded audio and subtitle track mpv finds is listed
// for the pickers, with what the shared audio ranking needs; QML does the rest (progress,
// next episode).
class VideoView : public QQuickFramebufferObject
{
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(double position READ position NOTIFY positionChanged)
    Q_PROPERTY(double duration READ duration NOTIFY durationChanged)
    Q_PROPERTY(bool playing READ playing NOTIFY playingChanged)
    Q_PROPERTY(bool loading READ loading NOTIFY loadingChanged)
    Q_PROPERTY(double speed READ speed WRITE setSpeed NOTIFY speedChanged)
    /** 0 to 1 (mpv's volume 0 to 100), for fading out live break music. */
    Q_PROPERTY(double volume READ volume WRITE setVolume NOTIFY volumeChanged)
    /** Audio and subtitle tracks: {id, type ("audio" / "sub"), label, language, selected, …}. */
    Q_PROPERTY(QVariantList audioTracks READ audioTracks NOTIFY tracksChanged)
    Q_PROPERTY(QVariantList subtitleTracks READ subtitleTracks NOTIFY tracksChanged)

public:
    explicit VideoView(QQuickItem *parent = nullptr);
    ~VideoView() override;
    Renderer *createRenderer() const override;

    /**
     * Plays [url] from [startSeconds], sending [authorization] ("Bearer …", or "" for none).
     * A Takeout stream URL redirects to presigned storage: that's followed once here and mpv
     * reads storage directly (mpv would go back to Takeout for every range it reads), asking
     * again before a seek once the URL is old, or when storage stops answering.
     */
    Q_INVOKABLE void load(const QString &url, const QString &authorization, double startSeconds);
    Q_INVOKABLE void togglePlay();
    Q_INVOKABLE void pause();
    Q_INVOKABLE void seek(double seconds);
    /** Relative to where it is. */
    Q_INVOKABLE void skip(double seconds);
    Q_INVOKABLE void setAudio(int id);
    /** 0 turns subtitles off. */
    Q_INVOKABLE void setSubtitle(int id);
    Q_INVOKABLE void stop();

    double position() const { return m_position; }
    double duration() const { return m_duration; }
    bool playing() const { return m_playing; }
    bool loading() const { return m_loading; }
    double speed() const { return m_speed; }
    void setSpeed(double speed);
    double volume() const { return m_volume; }
    void setVolume(double volume);
    QVariantList audioTracks() const { return m_audio; }
    QVariantList subtitleTracks() const { return m_subtitles; }

    mpv_handle *mpv() const { return m_mpv; }
    mpv_render_context *renderContext = nullptr;

Q_SIGNALS:
    void positionChanged();
    void durationChanged();
    void playingChanged();
    void loadingChanged();
    void speedChanged();
    void volumeChanged();
    void tracksChanged();
    /** Played to its end. */
    void ended();
    /** Couldn't be played; [message] says why. */
    void failed(const QString &message);

private:
    void onEvents();
    /** Finds where m_url redirects, then plays that from [startSeconds] ([keepPaused]: as it was). */
    void resolve(double startSeconds, bool keepPaused);
    /** Has mpv play [url] from [startSeconds], with [authorization] (or none). */
    void play(const QString &url, const QString &authorization, double startSeconds, bool keepPaused);
    /** Whether the storage URL being played is too old to open again. */
    bool targetStale() const;
    void tracksUpdated(const struct mpv_node *list);

    mpv_handle *m_mpv = nullptr;
    double m_position = 0;
    double m_duration = 0;
    bool m_paused = false;
    bool m_idle = true;
    bool m_playing = false;
    bool m_loading = false;
    double m_speed = 1;
    double m_volume = 1;
    QVariantList m_audio;
    QVariantList m_subtitles;

    /** The stream being played as given to load(), and its redirect's storage URL. */
    QString m_url;
    QString m_authorization;
    QString m_target;
    /** When m_target should stop being used (epoch ms). */
    qint64 m_targetUntil = 0;
    /**
     * mpv's playlist entry for the latest loadfile, from its reply; -1 until then. End events
     * of earlier files (one replaced while it was still opening) are ignored.
     */
    qint64 m_entry = -1;
    /** Whether a failed read was already retried with a fresh redirect. */
    bool m_retried = false;
    /** Bumped by each load, so a late redirect for an earlier one is dropped. */
    int m_generation = 0;
    class QNetworkAccessManager *m_network = nullptr;
};
