#include "VideoView.h"

#include <QDateTime>
#include <QDebug>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QTimeZone>
#include <QUrlQuery>
#include <QLocale>
#include <QOpenGLContext>
#include <QOpenGLFramebufferObject>
#include <QQuickWindow>
#include <mpv/client.h>
#include <mpv/render_gl.h>

namespace {
void *procAddress(void *, const char *name)
{
    QOpenGLContext *context = QOpenGLContext::currentContext();
    return context ? reinterpret_cast<void *>(context->getProcAddress(QByteArray(name))) : nullptr;
}

class MpvRenderer : public QQuickFramebufferObject::Renderer
{
public:
    explicit MpvRenderer(VideoView *view) : m_view(view) {}

    QOpenGLFramebufferObject *createFramebufferObject(const QSize &size) override
    {
        // mpv's renderer is made on the render thread, with Qt Quick's GL context current.
        if (!m_view->renderContext) {
            mpv_opengl_init_params gl{procAddress, nullptr};
            int advanced = 1;
            mpv_render_param params[] = {
                {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
                {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl},
                {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
                {MPV_RENDER_PARAM_INVALID, nullptr},
            };
            if (mpv_render_context_create(&m_view->renderContext, m_view->mpv(), params) < 0) {
                qWarning() << "mpv: can't render video";
            } else {
                mpv_render_context_set_update_callback(m_view->renderContext, [](void *ctx) {
                    QMetaObject::invokeMethod(static_cast<VideoView *>(ctx), &QQuickItem::update, Qt::QueuedConnection);
                }, m_view);
            }
        }
        return QQuickFramebufferObject::Renderer::createFramebufferObject(size);
    }

    void render() override
    {
        if (!m_view->renderContext) return;
        QOpenGLFramebufferObject *fbo = framebufferObject();
        mpv_opengl_fbo target{static_cast<int>(fbo->handle()), fbo->width(), fbo->height(), 0};
        int flip = 0;
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &target},
            {MPV_RENDER_PARAM_FLIP_Y, &flip},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        mpv_render_context_render(m_view->renderContext, params);
    }

private:
    VideoView *m_view;
};

const mpv_node *field(const mpv_node &map, const char *key)
{
    if (map.format != MPV_FORMAT_NODE_MAP) return nullptr;
    for (int i = 0; i < map.u.list->num; ++i)
        if (qstrcmp(map.u.list->keys[i], key) == 0) return &map.u.list->values[i];
    return nullptr;
}

QString string(const mpv_node &map, const char *key)
{
    const mpv_node *n = field(map, key);
    return n && n->format == MPV_FORMAT_STRING ? QString::fromUtf8(n->u.string) : QString();
}

qint64 integer(const mpv_node &map, const char *key)
{
    const mpv_node *n = field(map, key);
    return n && n->format == MPV_FORMAT_INT64 ? n->u.int64 : 0;
}

bool flag(const mpv_node &map, const char *key)
{
    const mpv_node *n = field(map, key);
    return n && n->format == MPV_FORMAT_FLAG && n->u.flag;
}

/** "English" for "eng" / "en"; "" for none or undetermined. */
QString languageName(const QString &code)
{
    if (code.isEmpty() || code == "und") return {};
    const QLocale::Language language = QLocale::codeToLanguage(code, QLocale::AnyLanguageCode);
    if (language == QLocale::AnyLanguage || language == QLocale::C) return code;
    return QLocale::languageToString(language);
}

/** A subtitle format's name ("PGS"). */
QString subtitleFormat(const QString &codec)
{
    static const QHash<QString, QString> names{
        {"subrip", "SRT"}, {"ass", "ASS"}, {"ssa", "SSA"}, {"webvtt", "WebVTT"},
        {"hdmv_pgs_subtitle", "PGS"}, {"dvd_subtitle", "VobSub"}, {"dvb_subtitle", "DVB"},
        {"mov_text", "Timed Text"}};
    return names.value(codec);
}
}

VideoView::VideoView(QQuickItem *parent)
    : QQuickFramebufferObject(parent)
{
    m_mpv = mpv_create();
    for (auto [name, value] : std::initializer_list<std::pair<const char *, const char *>>{
             {"vo", "libmpv"}, {"hwdec", "auto-safe"}, {"idle", "yes"}, {"keep-open", "no"},
             {"terminal", "no"}, {"input-default-bindings", "no"}, {"ytdl", "no"}, {"cache", "yes"},
             // Subtitles only when picked; the file's default (forced) ones still show.
             {"sid", "auto"}, {"audio-client-name", "TakeoutFM"},
             {"user-agent", "TakeoutFM-Linux/" PROJECT_VERSION}})
        mpv_set_option_string(m_mpv, name, value);
    if (mpv_initialize(m_mpv) < 0) qWarning() << "mpv didn't start";
    mpv_observe_property(m_mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "pause", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "idle-active", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "paused-for-cache", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "speed", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "track-list", MPV_FORMAT_NODE);
    mpv_set_wakeup_callback(m_mpv, [](void *ctx) {
        QMetaObject::invokeMethod(static_cast<VideoView *>(ctx), &VideoView::onEvents, Qt::QueuedConnection);
    }, this);
}

VideoView::~VideoView()
{
    mpv_set_wakeup_callback(m_mpv, nullptr, nullptr);
    if (renderContext) mpv_render_context_free(renderContext);
    mpv_terminate_destroy(m_mpv);
}

QQuickFramebufferObject::Renderer *VideoView::createRenderer() const
{
    window()->setPersistentGraphics(true);
    return new MpvRenderer(const_cast<VideoView *>(this));
}

namespace {
// A storage URL is used again for at most this long, and stops a minute before it says it expires.
constexpr qint64 MaxReuseMs = 10 * 60 * 1000;
constexpr qint64 MarginMs = 60 * 1000;

/** When a presigned S3 URL stops working (epoch ms), or 0 if it doesn't say. */
qint64 expiresAt(const QUrl &url)
{
    const QUrlQuery query(url);
    const QDateTime signedAt = QDateTime::fromString(query.queryItemValue("X-Amz-Date"), "yyyyMMdd'T'HHmmss'Z'");
    bool ok = false;
    const qint64 seconds = query.queryItemValue("X-Amz-Expires").toLongLong(&ok);
    if (!signedAt.isValid() || !ok) return 0;
    QDateTime utc = signedAt;
    utc.setTimeZone(QTimeZone::UTC);
    return utc.toMSecsSinceEpoch() + seconds * 1000;
}
}

void VideoView::load(const QString &url, const QString &authorization, double startSeconds)
{
    ++m_generation;
    m_url = url;
    m_authorization = authorization;
    m_target.clear();
    m_targetUntil = 0;
    m_retried = false;
    m_loading = true;
    Q_EMIT loadingChanged();
    // Only Takeout's streams (they have a token); a downloaded file plays as it is.
    if (authorization.isEmpty() || !url.startsWith("http")) {
        play(url, authorization, startSeconds, false);
        return;
    }
    resolve(startSeconds, false);
}

void VideoView::resolve(double startSeconds, bool keepPaused)
{
    if (!m_network) m_network = new QNetworkAccessManager(this);
    const int generation = m_generation;
    QNetworkRequest request{QUrl(m_url)};
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    request.setRawHeader("Authorization", m_authorization.toUtf8());
    // only the redirect is wanted, not the file
    request.setRawHeader("Range", "bytes=0-0");
    request.setTransferTimeout(15000);
    QNetworkReply *reply = m_network->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation, startSeconds, keepPaused] {
        reply->deleteLater();
        if (generation != m_generation) return;
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QUrl location = reply->header(QNetworkRequest::LocationHeader).toUrl();
        if (status >= 300 && status < 400 && location.isValid()) {
            const QUrl target = QUrl(m_url).resolved(location);
            const qint64 now = QDateTime::currentMSecsSinceEpoch();
            const qint64 expires = expiresAt(target);
            m_target = target.toString(QUrl::FullyEncoded);
            m_targetUntil = std::min(now + MaxReuseMs, expires > 0 ? expires - MarginMs : now + MaxReuseMs);
            // presigned: storage wants no token of Takeout's
            play(m_target, QString(), startSeconds, keepPaused);
        } else {
            // not a redirect (or no answer): mpv asks Takeout itself, as before
            m_target.clear();
            play(m_url, m_authorization, startSeconds, keepPaused);
        }
    });
}

bool VideoView::targetStale() const
{
    return !m_target.isEmpty() && QDateTime::currentMSecsSinceEpoch() >= m_targetUntil;
}

void VideoView::play(const QString &url, const QString &authorization, double startSeconds, bool keepPaused)
{
    const QByteArray header = authorization.isEmpty() ? QByteArray() : "Authorization: " + authorization.toUtf8();
    mpv_set_property_string(m_mpv, "http-header-fields", header.constData());
    const QByteArray target = url.toUtf8();
    QByteArrayList options;
    if (startSeconds > 0) options << "start=" + QByteArray::number(startSeconds);
    if (keepPaused) {
        // the same title again: keep the tracks chosen
        for (const char *track : {"aid", "sid"}) {
            char *value = mpv_get_property_string(m_mpv, track);
            if (value) {
                options << QByteArray(track) + "=" + value;
                mpv_free(value);
            }
        }
    }
    const QByteArray joined = options.join(',');
    const char *args[] = {"loadfile", target.constData(), "replace", "-1", joined.constData(), nullptr};
    if (int error = mpv_command(m_mpv, args); error < 0) Q_EMIT failed(QString::fromUtf8(mpv_error_string(error)));
    if (!keepPaused) {
        int no = 0;
        mpv_set_property(m_mpv, "pause", MPV_FORMAT_FLAG, &no);
    }
}

void VideoView::togglePlay()
{
    int pause = m_paused ? 0 : 1;
    mpv_set_property(m_mpv, "pause", MPV_FORMAT_FLAG, &pause);
}

void VideoView::pause()
{
    int pause = 1;
    mpv_set_property(m_mpv, "pause", MPV_FORMAT_FLAG, &pause);
}

void VideoView::seek(double seconds)
{
    if (targetStale()) {
        // the storage URL may have expired: a fresh one, from there
        resolve(std::max(0.0, seconds), true);
        return;
    }
    const QByteArray to = QByteArray::number(std::max(0.0, seconds));
    const char *args[] = {"seek", to.constData(), "absolute", nullptr};
    mpv_command_async(m_mpv, 0, args);
}

void VideoView::skip(double seconds)
{
    if (targetStale()) {
        resolve(std::max(0.0, m_position + seconds), true);
        return;
    }
    const QByteArray by = QByteArray::number(seconds);
    const char *args[] = {"seek", by.constData(), "relative", nullptr};
    mpv_command_async(m_mpv, 0, args);
}

void VideoView::setAudio(int id)
{
    int64_t value = id;
    mpv_set_property(m_mpv, "aid", MPV_FORMAT_INT64, &value);
}

void VideoView::setSubtitle(int id)
{
    if (id <= 0) {
        mpv_set_property_string(m_mpv, "sid", "no");
    } else {
        int64_t value = id;
        mpv_set_property(m_mpv, "sid", MPV_FORMAT_INT64, &value);
    }
}

void VideoView::setSpeed(double speed)
{
    mpv_set_property(m_mpv, "speed", MPV_FORMAT_DOUBLE, &speed);
}

void VideoView::setVolume(double volume)
{
    volume = qBound(0.0, volume, 1.0);
    if (qAbs(volume - m_volume) < 0.001) return;
    m_volume = volume;
    double percent = volume * 100;
    // Not waiting for mpv: set while it loads a file it can block the UI thread for good (the
    // live player sets it at every tune and through a fade). mpv copies the value.
    mpv_set_property_async(m_mpv, 0, "volume", MPV_FORMAT_DOUBLE, &percent);
    Q_EMIT volumeChanged();
}

void VideoView::stop()
{
    const char *args[] = {"stop", nullptr};
    mpv_command(m_mpv, args);
}

void VideoView::onEvents()
{
    while (m_mpv) {
        mpv_event *event = mpv_wait_event(m_mpv, 0);
        if (event->event_id == MPV_EVENT_NONE) break;
        switch (event->event_id) {
        case MPV_EVENT_PROPERTY_CHANGE: {
            auto *p = static_cast<mpv_event_property *>(event->data);
            const QByteArray name(p->name);
            const bool none = p->format == MPV_FORMAT_NONE;
            if (name == "time-pos") {
                m_position = none ? 0 : *static_cast<double *>(p->data);
                Q_EMIT positionChanged();
            } else if (name == "duration") {
                m_duration = none ? 0 : *static_cast<double *>(p->data);
                Q_EMIT durationChanged();
            } else if (name == "pause" || name == "idle-active") {
                const bool on = !none && *static_cast<int *>(p->data);
                (name == "pause" ? m_paused : m_idle) = on;
                const bool playing = !m_paused && !m_idle;
                if (playing != m_playing) {
                    m_playing = playing;
                    Q_EMIT playingChanged();
                }
            } else if (name == "paused-for-cache") {
                const bool buffering = !none && *static_cast<int *>(p->data);
                if (buffering != m_loading) {
                    m_loading = buffering;
                    Q_EMIT loadingChanged();
                }
            } else if (name == "speed" && !none) {
                m_speed = *static_cast<double *>(p->data);
                Q_EMIT speedChanged();
            } else if (name == "track-list" && !none) {
                tracksUpdated(static_cast<mpv_node *>(p->data));
            }
            break;
        }
        case MPV_EVENT_PLAYBACK_RESTART:
            if (m_loading) {
                m_loading = false;
                Q_EMIT loadingChanged();
            }
            break;
        case MPV_EVENT_END_FILE: {
            auto *end = static_cast<mpv_event_end_file *>(event->data);
            if (end->reason == MPV_END_FILE_REASON_EOF) Q_EMIT ended();
            else if (end->reason == MPV_END_FILE_REASON_ERROR) {
                // Storage stopped answering (most likely its URL expired over a long pause):
                // once, a fresh redirect from where it was.
                if (!m_target.isEmpty() && !m_retried) {
                    m_retried = true;
                    resolve(m_position, true);
                } else {
                    Q_EMIT failed(QString::fromUtf8(mpv_error_string(end->error)));
                }
            }
            break;
        }
        default:
            break;
        }
    }
}

void VideoView::tracksUpdated(const mpv_node *list)
{
    QVariantList audio;
    QVariantList subtitles;
    if (list->format == MPV_FORMAT_NODE_ARRAY) {
        for (int i = 0; i < list->u.list->num; ++i) {
            const mpv_node &track = list->u.list->values[i];
            const QString type = string(track, "type");
            if (type != "audio" && type != "sub") continue;
            const QString language = string(track, "lang");
            const QString title = string(track, "title");
            const QString codec = string(track, "codec");
            const QString profile = string(track, "codec-profile");
            const int id = int(integer(track, "id"));
            const QString languageText = languageName(language);
            QVariantMap item{
                {"id", id},
                {"language", language},
                {"selected", flag(track, "selected")},
            };
            if (type == "audio") {
                // Labelled and ranked by the shared core (Core.call("audioTracks")); until it
                // answers, the language is the label.
                item.insert("label", languageText.isEmpty() ? (title.isEmpty() ? QStringLiteral("Track %1").arg(audio.size() + 1) : title) : languageText);
                item.insert("info", QVariantMap{
                    {"codec", codec},
                    {"profile", profile},
                    {"channels", integer(track, "demux-channel-count")},
                    {"language", language.isEmpty() ? QVariant() : QVariant(language)},
                    {"languageName", languageText.isEmpty() ? QVariant() : QVariant(languageText)},
                    {"title", title.isEmpty() ? QVariant() : QVariant(title)},
                    {"bitrate", integer(track, "demux-bitrate")},
                    {"isDefault", flag(track, "default")},
                });
                audio << item;
            } else {
                // e.g. "English · SDH · PGS".
                QStringList parts;
                if (!languageText.isEmpty() && !title.contains(languageText, Qt::CaseInsensitive)) parts << languageText;
                if (!title.isEmpty()) parts << title;
                if (flag(track, "forced")) parts << QStringLiteral("Forced");
                if (flag(track, "hearing-impaired")) parts << QStringLiteral("SDH");
                const QString format = subtitleFormat(codec);
                if (!format.isEmpty() && !title.contains(format, Qt::CaseInsensitive)) parts << format;
                parts.removeAll(QString());
                parts.removeDuplicates();
                item.insert("label", parts.isEmpty() ? QStringLiteral("Track %1").arg(id) : parts.join(QStringLiteral(" · ")));
                subtitles << item;
            }
        }
    }
    m_audio = audio;
    m_subtitles = subtitles;
    Q_EMIT tracksChanged();
}
