#include "Player.h"

#include "Core.h"

#include <QDebug>
#include <algorithm>
#include <memory>
#include <mpv/client.h>
#include <random>

namespace {
// mpv_node trees for mpv_command_node, owning their strings.
struct Node {
    std::vector<std::unique_ptr<QByteArray>> strings;
    std::vector<std::unique_ptr<std::vector<mpv_node>>> lists;
    std::vector<std::unique_ptr<std::vector<char *>>> keys;

    mpv_node string(const QString &value)
    {
        strings.push_back(std::make_unique<QByteArray>(value.toUtf8()));
        mpv_node n{};
        n.format = MPV_FORMAT_STRING;
        n.u.string = strings.back()->data();
        return n;
    }

    static mpv_node integer(int64_t value)
    {
        mpv_node n{};
        n.format = MPV_FORMAT_INT64;
        n.u.int64 = value;
        return n;
    }

    mpv_node map(const QList<std::pair<QString, mpv_node>> &entries)
    {
        auto values = std::make_unique<std::vector<mpv_node>>();
        auto names = std::make_unique<std::vector<char *>>();
        for (const auto &[key, value] : entries) {
            strings.push_back(std::make_unique<QByteArray>(key.toUtf8()));
            names->push_back(strings.back()->data());
            values->push_back(value);
        }
        auto *list = new mpv_node_list{};
        list->num = int(values->size());
        list->values = values->data();
        list->keys = names->data();
        lists.push_back(std::move(values));
        keys.push_back(std::move(names));
        owned.push_back(std::unique_ptr<mpv_node_list>(list));
        mpv_node n{};
        n.format = MPV_FORMAT_NODE_MAP;
        n.u.list = list;
        return n;
    }

    std::vector<std::unique_ptr<mpv_node_list>> owned;
};

void command(mpv_handle *mpv, std::initializer_list<const char *> args)
{
    std::vector<const char *> list(args);
    list.push_back(nullptr);
    if (int error = mpv_command(mpv, list.data()); error < 0)
        qWarning() << "mpv:" << list.front() << mpv_error_string(error);
}

void setString(mpv_handle *mpv, const char *name, const char *value)
{
    if (int error = mpv_set_property_string(mpv, name, value); error < 0)
        qWarning() << "mpv:" << name << mpv_error_string(error);
}

void setFlag(mpv_handle *mpv, const char *name, bool value)
{
    int flag = value ? 1 : 0;
    mpv_set_property(mpv, name, MPV_FORMAT_FLAG, &flag);
}

QString text(const QVariantMap &map, const char *key) { return map.value(key).toString(); }
}

namespace {
Player *instance = nullptr;
}

Player *Player::create(QQmlEngine *, QJSEngine *)
{
    QJSEngine::setObjectOwnership(instance, QJSEngine::CppOwnership);
    return instance;
}

Player::Player(Core *core, QObject *parent)
    : QObject(parent)
    , m_core(core)
{
    instance = this;
    m_mpv = mpv_create();
    // Music only (L5's video gets its own); cover art embedded in files isn't shown as video.
    for (auto [name, value] : std::initializer_list<std::pair<const char *, const char *>>{
             {"vid", "no"}, {"audio-display", "no"}, {"idle", "yes"}, {"terminal", "no"},
             {"input-default-bindings", "no"}, {"ytdl", "no"}, {"prefetch-playlist", "yes"},
             {"gapless-audio", "weak"}, {"cache", "yes"}, {"audio-client-name", "TakeoutFM"},
             {"user-agent", "TakeoutFM-Linux/" PROJECT_VERSION}})
        mpv_set_option_string(m_mpv, name, value);
    if (mpv_initialize(m_mpv) < 0) qWarning() << "mpv didn't start";

    mpv_observe_property(m_mpv, 0, "playlist-pos", MPV_FORMAT_INT64);
    mpv_observe_property(m_mpv, 0, "pause", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "idle-active", MPV_FORMAT_FLAG);
    mpv_observe_property(m_mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "duration", MPV_FORMAT_DOUBLE);
    mpv_observe_property(m_mpv, 0, "metadata/by-key/icy-title", MPV_FORMAT_STRING);
    mpv_set_wakeup_callback(m_mpv, [](void *ctx) {
        QMetaObject::invokeMethod(static_cast<Player *>(ctx), &Player::onEvents, Qt::QueuedConnection);
    }, this);

    m_ticker.setInterval(500);
    connect(&m_ticker, &QTimer::timeout, this, &Player::tick);
    m_savedAt.start();
    m_episodeSavedAt.start();
}

Player::~Player()
{
    saveProgress();
    saveEpisode();
    mpv_set_wakeup_callback(m_mpv, nullptr, nullptr);
    mpv_terminate_destroy(m_mpv);
}

QVariantMap Player::current() const
{
    return m_index >= 0 && m_index < m_items.size() ? m_items.at(m_index).toMap() : QVariantMap{};
}

std::optional<std::pair<QString, QString>> Player::splitStreamTitle(const QString &icy)
{
    // As the shared parseStreamTitle.
    const qsizetype i = icy.indexOf(QLatin1String(" - "));
    if (i <= 0) return std::nullopt;
    const QString artist = icy.left(i).trimmed();
    const QString title = icy.mid(i + 3).trimmed();
    if (artist.isEmpty() || title.isEmpty()) return std::nullopt;
    return std::make_pair(artist, title);
}

QString Player::displayTitle() const
{
    const QVariantMap item = current();
    if (m_streamTitle.isEmpty()) return text(item, "title");
    const auto song = splitStreamTitle(m_streamTitle);
    return song ? song->second : m_streamTitle;
}

QString Player::displaySubtitle() const
{
    const QVariantMap item = current();
    if (m_streamTitle.isEmpty()) return text(item, "artist");
    const auto song = splitStreamTitle(m_streamTitle);
    return song ? song->first + QStringLiteral(" · ") + text(item, "title") : text(item, "title");
}

void Player::play(const QVariantMap &queue, bool shuffled, int start)
{
    saveProgress();
    saveEpisode();
    const int generation = ++m_generation;
    QVariantList list = queue.value("items").toList();
    int index = start >= 0 ? start : queue.value("startIndex").toInt();
    double seconds = start >= 0 ? 0 : queue.value("startPositionMs").toLongLong() / 1000.0;
    if (shuffled) {
        std::shuffle(list.begin(), list.end(), std::mt19937(std::random_device{}()));
        index = 0;
        seconds = 0;
    }
    if (index < 0 || index >= list.size()) return;

    m_historyId.clear();
    m_core->call("recordPlayed", {{"queue", queue}}, [this, generation](const QVariant &id, const QVariant &) {
        if (generation == m_generation) m_historyId = id.toString();
    });
    m_items = list;
    m_queueTitle = text(queue, "title");
    Q_EMIT itemsChanged();
    // The media token, for the streams on the signed-in server (never sent elsewhere).
    m_core->call("mediaAuthorization", {{"url", m_core->endpoint() + "/api/"}},
                 [this, generation, index, seconds](const QVariant &auth, const QVariant &) {
        if (generation != m_generation) return;
        m_authorization = auth.toString();
        load(index, seconds);
    });
}

void Player::load(int start, double seconds)
{
    command(m_mpv, {"stop"});
    m_index = -1;
    for (int i = 0; i < m_items.size(); ++i)
        loadFile(m_items.at(i).toMap(), i == start ? "append-play" : "append", -1, i == start ? seconds : 0);
    setFlag(m_mpv, "pause", false);
}

void Player::loadFile(const QVariantMap &item, const char *flag, int at, double seconds)
{
    // Downloaded: play the file (offline too).
    const QString local = m_core->localFile(text(item, "etag"));
    const QString uri = local.isEmpty() ? text(item, "uri") : local;
    QList<std::pair<QString, mpv_node>> options;
    Node node;
    const QString endpoint = m_core->endpoint();
    if (!m_authorization.isEmpty() && !endpoint.isEmpty() && uri.startsWith(endpoint + "/"))
        options << std::make_pair(QStringLiteral("http-header-fields"), node.string("Authorization: " + m_authorization));
    if (seconds > 0) options << std::make_pair(QStringLiteral("start"), node.string(QString::number(seconds)));
    mpv_node args = node.map({
        {"name", node.string("loadfile")},
        {"url", node.string(uri)},
        {"flags", node.string(QString::fromLatin1(flag))},
        {"index", Node::integer(at)},
        {"options", node.map(options)},
    });
    mpv_node result{};
    if (int error = mpv_command_node(m_mpv, &args, &result); error < 0)
        qWarning() << "mpv: can't load" << uri << mpv_error_string(error);
    else
        mpv_free_node_contents(&result);
}

void Player::enqueue(const QVariantList &items, bool last)
{
    if (items.isEmpty()) return;
    if (m_items.isEmpty()) {
        play({{"title", items.first().toMap().value("title")}, {"items", items}});
        return;
    }
    const int at = last ? int(m_items.size()) : m_index + 1;
    for (int k = 0; k < items.size(); ++k) {
        m_items.insert(at + k, items.at(k));
        loadFile(items.at(k).toMap(), last ? "append" : "insert-at", at + k, 0);
    }
    Q_EMIT itemsChanged();
}

void Player::togglePlay()
{
    if (m_playing) pause(); else resume();
}

void Player::resume()
{
    if (m_items.isEmpty()) return;
    // Played to the end: start the last item again.
    if (m_idle) load(std::clamp(m_index, 0, int(m_items.size()) - 1), 0);
    else setFlag(m_mpv, "pause", false);
}

void Player::pause()
{
    setFlag(m_mpv, "pause", true);
}

void Player::next()
{
    if (m_index + 1 >= m_items.size()) return;
    skipTo(m_index + 1);
}

void Player::previous()
{
    if (m_position > 3 || m_index <= 0) seek(0);
    else skipTo(m_index - 1);
}

void Player::skipTo(int index)
{
    if (index < 0 || index >= m_items.size() || (index == m_index && !m_idle)) return;
    saveEpisode();
    if (m_idle) {
        load(index, 0);
        return;
    }
    int64_t pos = index;
    mpv_set_property(m_mpv, "playlist-pos", MPV_FORMAT_INT64, &pos);
    setFlag(m_mpv, "pause", false);
}

void Player::seek(double seconds)
{
    const QByteArray to = QByteArray::number(std::max(0.0, seconds));
    command(m_mpv, {"seek", to.constData(), "absolute"});
    m_position = seconds;
    Q_EMIT positionChanged();
    Q_EMIT seeked();
}

void Player::cycleRepeat()
{
    setRepeatMode(RepeatMode((m_repeat + 1) % 3));
}

void Player::setRepeatMode(RepeatMode mode)
{
    if (mode == m_repeat) return;
    m_repeat = mode;
    setString(m_mpv, "loop-file", mode == RepeatOne ? "inf" : "no");
    setString(m_mpv, "loop-playlist", mode == RepeatAll ? "inf" : "no");
    Q_EMIT repeatModeChanged();
}

void Player::stop()
{
    saveProgress();
    saveEpisode();
    ++m_generation;
    command(m_mpv, {"stop"});
    m_items.clear();
    m_queueTitle.clear();
    m_index = -1;
    m_historyId.clear();
    m_streamTitle.clear();
    report("listenTrack", {{"item", QVariant()}});
    Q_EMIT itemsChanged();
    Q_EMIT currentChanged();
}

void Player::onEvents()
{
    while (m_mpv) {
        mpv_event *event = mpv_wait_event(m_mpv, 0);
        if (event->event_id == MPV_EVENT_NONE) break;
        switch (event->event_id) {
        case MPV_EVENT_PROPERTY_CHANGE: {
            auto *p = static_cast<mpv_event_property *>(event->data);
            const QByteArray name(p->name);
            const bool none = p->format == MPV_FORMAT_NONE;
            if (name == "playlist-pos") {
                indexChanged(none ? -1 : int(*static_cast<int64_t *>(p->data)));
            } else if (name == "pause") {
                m_paused = !none && *static_cast<int *>(p->data);
                updatePlaying();
            } else if (name == "idle-active") {
                m_idle = !none && *static_cast<int *>(p->data);
                updatePlaying();
            } else if (name == "time-pos") {
                m_position = none ? 0 : *static_cast<double *>(p->data);
                Q_EMIT positionChanged();
            } else if (name == "duration") {
                // A stream's "duration" is only what's been buffered.
                m_duration = none || isStream() ? 0 : *static_cast<double *>(p->data);
                m_live = m_loaded && (isStream() || m_duration <= 0);
                Q_EMIT durationChanged();
            } else if (name == "metadata/by-key/icy-title" && !none) {
                streamTitleChanged(QString::fromUtf8(*static_cast<char **>(p->data)));
            }
            break;
        }
        case MPV_EVENT_START_FILE:
            m_loaded = false;
            break;
        case MPV_EVENT_FILE_LOADED:
            m_loaded = true;
            // A live stream has no end.
            m_live = isStream() || m_duration <= 0;
            Q_EMIT durationChanged();
            break;
        case MPV_EVENT_END_FILE: {
            auto *end = static_cast<mpv_event_end_file *>(event->data);
            if (end->reason == MPV_END_FILE_REASON_EOF) {
                ended(end->playlist_entry_id);
            } else if (end->reason == MPV_END_FILE_REASON_ERROR) {
                qWarning() << "Can't play" << text(current(), "uri") << mpv_error_string(end->error);
                report("listenStopped", {});
            }
            break;
        }
        default:
            break;
        }
    }
}

void Player::indexChanged(int index)
{
    // Played to the end (mpv went idle): stay on the last item.
    if (index < 0 && !m_items.isEmpty()) return;
    if (index == m_index) return;
    m_index = index;
    m_streamTitle.clear();
    m_live = false;
    m_episodeSavedAt.restart(); // an episode's first save comes 10 s in
    report("listenTrack", {{"item", index >= 0 ? QVariant(current()) : QVariant()}});
    Q_EMIT currentChanged();
    Q_EMIT durationChanged();
}

bool Player::isStream() const
{
    // Radio streams are the items without an ETag.
    const QVariantMap item = current();
    return !item.isEmpty() && text(item, "etag").isEmpty();
}

void Player::updatePlaying()
{
    const bool playing = !m_paused && !m_idle && !m_items.isEmpty();
    if (playing == m_playing) return;
    m_playing = playing;
    report("listenPlaying", {{"playing", playing}, {"durationMs", durationMs()}});
    if (playing) {
        m_ticker.start();
    } else {
        m_ticker.stop();
        saveProgress();
        saveEpisode();
    }
    Q_EMIT playingChanged();
}

void Player::streamTitleChanged(const QString &title)
{
    if (title.isEmpty() || title == m_streamTitle) return;
    m_streamTitle = title;
    const QVariantMap item = current();
    report("listenStreamTitle", {{"title", title}});
    report("streamSongHeard", {{"station", text(item, "title")}, {"imageUri", text(item, "imageUri")}, {"title", title}});
    Q_EMIT currentChanged();
}

void Player::ended(qint64 entryId)
{
    // An episode that played to its end is complete.
    for (int i = 0; i < m_items.size(); ++i) {
        const QByteArray property = "playlist/" + QByteArray::number(i) + "/id";
        int64_t id = -1;
        if (mpv_get_property(m_mpv, property.constData(), MPV_FORMAT_INT64, &id) < 0 || id != entryId) continue;
        const QVariantMap item = m_items.at(i).toMap();
        if (item.value("podcast").toBool() && m_duration > 0)
            report("reportProgress", {{"etag", text(item, "etag")}, {"positionMs", durationMs()}, {"durationMs", durationMs()}});
        break;
    }
}

void Player::tick()
{
    report("listenTick", {{"durationMs", durationMs()}});
    if (m_savedAt.elapsed() > 5000) saveProgress();
    if (m_episodeSavedAt.elapsed() > 10000) saveEpisode();
}

/** Where Recently Played resumes this queue (a live stream starts afresh). */
void Player::saveProgress()
{
    if (m_historyId.isEmpty() || m_index < 0) return;
    m_savedAt.restart();
    report("playedProgress", {{"id", m_historyId}, {"index", m_index},
                              {"positionMs", m_live ? 0 : qint64(m_position * 1000)}});
}

/** Where a podcast episode got to (music tracks are left to the listen session). */
void Player::saveEpisode()
{
    const QVariantMap item = current();
    if (!item.value("podcast").toBool() || text(item, "etag").isEmpty() || m_position <= 0) return;
    m_episodeSavedAt.restart();
    report("reportProgress", {{"etag", text(item, "etag")}, {"positionMs", qint64(m_position * 1000)},
                              {"durationMs", durationMs()}});
}

void Player::report(const char *name, const QVariantMap &args)
{
    m_core->call(QString::fromLatin1(name), args, Core::Callback{});
}
