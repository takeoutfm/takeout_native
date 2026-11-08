#include "HttpHost.h"

#include "takeout.h"

#include <QCoreApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkProxyFactory>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QThread>
#include <QTimer>

struct HttpHost::Request {
    void *handle = nullptr;
    QNetworkReply *reply = nullptr;
    QTimer *timer = nullptr;
    int connectMs = 0;
    int socketMs = 0;
    /** The core set no Accept-Encoding, so Qt asks for gzip and decodes it itself. */
    bool qtDecodes = true;
    bool headSent = false;
    bool paused = false;
    bool finished = false;
    bool timedOut = false;
};

namespace {
QByteArray errorJson(const char *kind, const QString &message)
{
    return QJsonDocument(QJsonObject{{"kind", QString::fromLatin1(kind)}, {"message", message}}).toJson(QJsonDocument::Compact);
}

/** A failure of the connection, not an HTTP status (which Qt also reports as an error). */
bool transportError(QNetworkReply *reply)
{
    const auto error = reply->error();
    if (error == QNetworkReply::NoError) return false;
    if (!reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).isValid()) return true;
    // Connection and proxy errors (below 200), and a broken response (say, cut short).
    return error < 200 || error == QNetworkReply::ProtocolFailure;
}
}

HttpHost::HttpHost(const Sink &sink)
    : m_sink(sink)
{
}

HttpHost *HttpHost::create(const Sink &sink)
{
    auto *thread = new QThread;
    thread->setObjectName(QStringLiteral("takeout-http"));
    auto *host = new HttpHost(sink);
    host->moveToThread(thread);
    // Stopped when the app quits; requests the core makes after that are never answered.
    if (auto *app = QCoreApplication::instance()) {
        QObject::connect(app, &QCoreApplication::aboutToQuit, thread, [thread] {
            thread->quit();
            thread->wait();
        }, Qt::DirectConnection);
    }
    thread->start();
    return host;
}

void HttpHost::install()
{
    QNetworkProxyFactory::setUseSystemConfiguration(true);
    static HttpHost *host = create({takeout_http_head, takeout_http_data, takeout_http_end});
    takeout_set_http(
        [](void *ctx, void *handle, const char *json, const void *body, size_t length) {
            static_cast<HttpHost *>(ctx)->start(handle, json, body, length);
        },
        [](void *ctx, void *handle) { static_cast<HttpHost *>(ctx)->cancel(handle); },
        [](void *ctx, void *handle) { static_cast<HttpHost *>(ctx)->resume(handle); },
        host);
}

void HttpHost::start(void *handle, const char *requestJson, const void *body, size_t length)
{
    QByteArray json(requestJson);
    QByteArray bytes = body && length ? QByteArray(static_cast<const char *>(body), qsizetype(length)) : QByteArray();
    QMetaObject::invokeMethod(this, [this, handle, json, bytes] { startNow(handle, json, bytes); }, Qt::QueuedConnection);
}

void HttpHost::cancel(void *handle)
{
    // Aborting ends it (finished → end, "cancelled"). Gone already: the host ended it first.
    QMetaObject::invokeMethod(this, [this, handle] {
        if (Request *request = m_requests.value(handle)) request->reply->abort();
    }, Qt::QueuedConnection);
}

void HttpHost::resume(void *handle)
{
    QMetaObject::invokeMethod(this, [this, handle] {
        Request *request = m_requests.value(handle);
        if (!request || !request->paused) return;
        request->paused = false;
        restartTimer(request);
        drain(request);
    }, Qt::QueuedConnection);
}

void HttpHost::startNow(void *handle, const QByteArray &json, const QByteArray &body)
{
    if (!m_network) m_network = new QNetworkAccessManager(this); // on this thread
    const QJsonObject spec = QJsonDocument::fromJson(json).object();
    auto *request = new Request;
    request->handle = handle;
    request->connectMs = spec.value("connectTimeoutMs").toInt();
    request->socketMs = spec.value("socketTimeoutMs").toInt();

    QNetworkRequest http(QUrl(spec.value("url").toString()));
    http.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    http.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::AlwaysNetwork);
    http.setAttribute(QNetworkRequest::CacheSaveControlAttribute, false);
    http.setAttribute(QNetworkRequest::CookieLoadControlAttribute, QNetworkRequest::Manual);
    http.setAttribute(QNetworkRequest::CookieSaveControlAttribute, QNetworkRequest::Manual);
    // A name given twice is sent once, its values joined (as HTTP allows).
    for (const QJsonValue &pair : spec.value("headers").toArray()) {
        const QByteArray name = pair.toArray().at(0).toString().toUtf8();
        const QByteArray value = pair.toArray().at(1).toString().toUtf8();
        if (name.compare("Accept-Encoding", Qt::CaseInsensitive) == 0) request->qtDecodes = false;
        http.setRawHeader(name, http.hasRawHeader(name) ? http.rawHeader(name) + ", " + value : value);
    }

    const QByteArray method = spec.value("method").toString().toLatin1();
    request->reply = body.isEmpty() ? m_network->sendCustomRequest(http, method)
                                    : m_network->sendCustomRequest(http, method, body);
    QNetworkReply *reply = request->reply;
    // Paused, Qt holds this much and then leaves the rest in the socket.
    reply->setReadBufferSize(ReadBuffer);
    request->timer = new QTimer(reply);
    request->timer->setSingleShot(true);
    connect(request->timer, &QTimer::timeout, reply, [request] {
        request->timedOut = true;
        request->reply->abort();
    });
    connect(reply, &QNetworkReply::metaDataChanged, this, [this, request] { sendHead(request); });
    connect(reply, &QNetworkReply::readyRead, this, [this, request] { drain(request); });
    connect(reply, &QNetworkReply::finished, this, [this, request] { finish(request); });
    m_requests.insert(handle, request);
    restartTimer(request);
}

void HttpHost::restartTimer(Request *request)
{
    // Until the head: connecting and the server's answer; then the gap between reads. Paused,
    // nothing is read, so no timer.
    const int ms = request->headSent ? request->socketMs : request->connectMs + request->socketMs;
    if (ms > 0 && !request->paused) request->timer->start(ms);
    else request->timer->stop();
}

void HttpHost::sendHead(Request *request)
{
    if (request->headSent || transportError(request->reply)) return;
    const QVariant status = request->reply->attribute(QNetworkRequest::HttpStatusCodeAttribute);
    if (!status.isValid()) return;
    bool decoded = false;
    if (request->qtDecodes) {
        for (const auto &[name, value] : request->reply->rawHeaderPairs())
            if (name.compare("Content-Encoding", Qt::CaseInsensitive) == 0 && !value.isEmpty() && value != "identity") decoded = true;
    }
    QJsonArray headers;
    for (const auto &[name, value] : request->reply->rawHeaderPairs()) {
        // Qt decoded the body, so its length and encoding are no longer the server's.
        if (decoded && (name.compare("Content-Length", Qt::CaseInsensitive) == 0 || name.compare("Content-Encoding", Qt::CaseInsensitive) == 0))
            continue;
        headers.append(QJsonArray{QString::fromLatin1(name), QString::fromUtf8(value)});
    }
    request->headSent = true;
    m_sink.head(request->handle, status.toInt(), QJsonDocument(headers).toJson(QJsonDocument::Compact).constData());
    restartTimer(request);
}

void HttpHost::drain(Request *request)
{
    if (request->paused) return;
    sendHead(request);
    if (!request->headSent) return;
    QNetworkReply *reply = request->reply;
    while (reply->bytesAvailable() > 0) {
        const QByteArray chunk = reply->read(64 * 1024);
        if (chunk.isEmpty()) break;
        if (m_sink.data(request->handle, chunk.constData(), size_t(chunk.size()))) {
            request->paused = true;
            restartTimer(request);
            return;
        }
        restartTimer(request);
    }
    if (request->finished && reply->bytesAvailable() == 0) end(request, {});
}

void HttpHost::finish(Request *request)
{
    request->finished = true;
    request->timer->stop();
    QNetworkReply *reply = request->reply;
    if (transportError(reply)) {
        const auto error = reply->error();
        if (request->timedOut) return end(request, errorJson("timeout", QStringLiteral("No answer in time")));
        if (error == QNetworkReply::OperationCanceledError) return end(request, errorJson("cancelled", reply->errorString()));
        if (error == QNetworkReply::TimeoutError) return end(request, errorJson("timeout", reply->errorString()));
        return end(request, errorJson("network", reply->errorString()));
    }
    sendHead(request);
    if (!request->headSent) return end(request, errorJson("network", QStringLiteral("No response")));
    // The rest of the body, if any; paused, resume finishes it.
    drain(request);
}

void HttpHost::end(Request *request, const QByteArray &errorJson)
{
    m_requests.remove(request->handle);
    m_sink.end(request->handle, errorJson.isEmpty() ? nullptr : errorJson.constData());
    request->reply->disconnect(this);
    request->timer->stop();
    request->reply->deleteLater();
    delete request;
}
