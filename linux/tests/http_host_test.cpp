// HttpHost (the shared core's HTTP done by Qt Network, PLAN.md L8) against a local HTTP server:
// its answers go to a recording sink here instead of libtakeout.so.

#include "HttpHost.h"

#include <QCoreApplication>
#include <QElapsedTimer>
#include <QHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutex>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>
#include <atomic>
#include <cstdio>
#include <functional>

namespace {
int failures = 0;

#define CHECK(condition) \
    do { \
        if (!(condition)) { \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
            ++failures; \
        } \
    } while (false)

// ---- The sink: what the host told the core, per handle ----

struct Answer {
    int heads = 0;
    int status = 0;
    QJsonArray headers;
    QByteArray body;
    int ends = 0;
    QJsonObject error;
    bool paused = false;
    qint64 consumed = 0; // by the pretend reader (pausing tests)
    qint64 mostAhead = 0;
    bool pauses = false; // ask for pauses past PauseAbove unread
};

constexpr qint64 PauseAbove = 1 << 20;
QMutex mutex;
QHash<void *, Answer> answers;

Answer answer(void *handle)
{
    QMutexLocker lock(&mutex);
    return answers.value(handle);
}

const HttpHost::Sink sink{
    [](void *handle, int status, const char *headers) {
        QMutexLocker lock(&mutex);
        Answer &a = answers[handle];
        ++a.heads;
        a.status = status;
        a.headers = QJsonDocument::fromJson(headers).array();
    },
    [](void *handle, const void *bytes, size_t length) -> int {
        QMutexLocker lock(&mutex);
        Answer &a = answers[handle];
        a.body.append(static_cast<const char *>(bytes), qsizetype(length));
        a.mostAhead = std::max(a.mostAhead, a.body.size() - a.consumed);
        if (a.pauses && a.body.size() - a.consumed > PauseAbove) a.paused = true;
        return a.paused ? 1 : 0;
    },
    [](void *handle, const char *error) {
        QMutexLocker lock(&mutex);
        Answer &a = answers[handle];
        ++a.ends;
        if (error) a.error = QJsonDocument::fromJson(error).object();
    },
};

QString header(const Answer &a, const QString &name)
{
    for (const QJsonValue &pair : a.headers)
        if (pair.toArray().at(0).toString().compare(name, Qt::CaseInsensitive) == 0) return pair.toArray().at(1).toString();
    return {};
}

bool waitFor(const std::function<bool()> &done, int ms = 10000)
{
    QElapsedTimer timer;
    timer.start();
    while (!done()) {
        if (timer.elapsed() > ms) return false;
        QCoreApplication::processEvents(QEventLoop::AllEvents, 5);
    }
    return true;
}

// ---- The server ----

struct Seen {
    QByteArray method, path, body;
    QHash<QByteArray, QByteArray> headers; // lower-case names
};

/** The 8 MB body's byte at [i]. */
char big(qint64 i) { return char(i % 251); }
constexpr qint64 BigSize = 8 << 20;

class Server : public QTcpServer
{
public:
    Seen last;

    Server() { listen(QHostAddress::LocalHost); }
    QByteArray url(const char *path) const { return "http://127.0.0.1:" + QByteArray::number(serverPort()) + path; }

protected:
    void incomingConnection(qintptr fd) override
    {
        auto *socket = new QTcpSocket(this);
        socket->setSocketDescriptor(fd);
        auto buffer = std::make_shared<QByteArray>();
        connect(socket, &QTcpSocket::readyRead, socket, [this, socket, buffer] {
            buffer->append(socket->readAll());
            const qsizetype headEnd = buffer->indexOf("\r\n\r\n");
            if (headEnd < 0) return;
            Seen seen;
            const QList<QByteArray> lines = buffer->left(headEnd).split('\n');
            const QList<QByteArray> requestLine = lines[0].trimmed().split(' ');
            seen.method = requestLine.value(0);
            seen.path = requestLine.value(1);
            for (qsizetype i = 1; i < lines.size(); ++i) {
                const qsizetype colon = lines[i].indexOf(':');
                if (colon > 0) seen.headers.insert(lines[i].left(colon).trimmed().toLower(), lines[i].mid(colon + 1).trimmed());
            }
            const qsizetype length = seen.headers.value("content-length").toLongLong();
            if (buffer->size() < headEnd + 4 + length) return;
            seen.body = buffer->mid(headEnd + 4, length);
            buffer->clear();
            last = seen;
            respond(socket, seen.path);
        });
    }

private:
    static void send(QTcpSocket *socket, const QByteArray &status, const QByteArray &headers, const QByteArray &body)
    {
        socket->write("HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: " + QByteArray::number(body.size()) +
                      "\r\nConnection: close\r\n\r\n" + body);
        socket->disconnectFromHost();
    }

    void respond(QTcpSocket *socket, const QByteArray &path)
    {
        if (path == "/json") {
            send(socket, "200 OK", "Content-Type: application/json\r\nX-Two: a\r\nX-Two: b\r\n", R"({"ok":true})");
        } else if (path == "/created") {
            send(socket, "201 Created", "", "made");
        } else if (path == "/redirect") {
            send(socket, "302 Found", "Location: https://storage.example/b\r\n", "");
        } else if (path == "/missing") {
            send(socket, "404 Not Found", "", "nope");
        } else if (path == "/deflate") {
            // HTTP "deflate" is a zlib stream: qCompress without its 4-byte length.
            const QByteArray text = QByteArray(4000, 'x');
            send(socket, "200 OK", "Content-Encoding: deflate\r\n", qCompress(text).mid(4));
        } else if (path == "/big") {
            QByteArray body(BigSize, Qt::Uninitialized);
            for (qint64 i = 0; i < BigSize; ++i) body[i] = big(i);
            send(socket, "200 OK", "Content-Type: application/octet-stream\r\n", body);
        } else if (path == "/slow") {
            // Never answers.
        } else if (path == "/stalls") {
            // The head and a little, then nothing.
            socket->write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nabc");
        } else if (path == "/broken") {
            socket->write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nabc");
            socket->flush();
            socket->disconnectFromHost();
        } else {
            send(socket, "500 Internal Server Error", "", "");
        }
    }
};

QByteArray request(const QByteArray &method, const QByteArray &url, const QJsonArray &headers = {}, int connectMs = 0, int socketMs = 0)
{
    QJsonObject json{{"method", QString::fromLatin1(method)}, {"url", QString::fromLatin1(url)}, {"headers", headers}};
    if (connectMs) json.insert("connectTimeoutMs", connectMs);
    if (socketMs) json.insert("socketTimeoutMs", socketMs);
    return QJsonDocument(json).toJson(QJsonDocument::Compact);
}

int nextHandle = 1;
void *newHandle() { return reinterpret_cast<void *>(quintptr(nextHandle++) * 16); }

/** Starts [json] and waits for its end. */
void *fetch(HttpHost *host, const QByteArray &json, const QByteArray &body = {})
{
    void *handle = newHandle();
    host->start(handle, json.constData(), body.isEmpty() ? nullptr : body.constData(), size_t(body.size()));
    if (!waitFor([handle] { return answer(handle).ends > 0; })) std::fprintf(stderr, "no end for %s\n", json.constData());
    return handle;
}
}

int main(int argc, char *argv[])
{
    QCoreApplication app(argc, argv);
    Server server;
    HttpHost *host = HttpHost::create(sink);

    // GET: the request's headers out, the response's back.
    {
        void *h = fetch(host, request("GET", server.url("/json"), {QJsonArray{"Authorization", "Bearer token"}, QJsonArray{"X-Many", "1"}, QJsonArray{"X-Many", "2"}}));
        const Answer a = answer(h);
        CHECK(server.last.method == "GET");
        CHECK(server.last.headers.value("authorization") == "Bearer token");
        CHECK(server.last.headers.value("x-many") == "1, 2");
        CHECK(!server.last.headers.contains("content-length"));
        CHECK(!server.last.headers.contains("cookie"));
        CHECK(a.heads == 1 && a.status == 200 && a.ends == 1 && a.error.isEmpty());
        CHECK(a.body == R"({"ok":true})");
        CHECK(header(a, "Content-Type") == "application/json");
        // Qt joins a repeated name's values, as HTTP allows.
        CHECK(header(a, "X-Two") == "a, b");
    }

    // POST: the body and its type.
    {
        void *h = fetch(host, request("POST", server.url("/created"), QJsonArray{QJsonArray{"Content-Type", "application/json"}}), R"({"a":1})");
        const Answer a = answer(h);
        CHECK(server.last.method == "POST");
        CHECK(server.last.body == R"({"a":1})");
        CHECK(server.last.headers.value("content-type") == "application/json");
        CHECK(a.status == 201 && a.body == "made" && a.error.isEmpty());
    }

    // A redirect isn't followed: the core sees it.
    {
        const Answer a = answer(fetch(host, request("GET", server.url("/redirect"))));
        CHECK(a.status == 302 && a.error.isEmpty());
        CHECK(header(a, "Location") == "https://storage.example/b");
    }

    // An HTTP error is a response, not a failure.
    {
        const Answer a = answer(fetch(host, request("GET", server.url("/missing"))));
        CHECK(a.heads == 1 && a.status == 404 && a.body == "nope" && a.error.isEmpty());
    }

    // Qt decodes a compressed body itself: the server's length and encoding don't go on.
    {
        const Answer a = answer(fetch(host, request("GET", server.url("/deflate"))));
        CHECK(a.status == 200 && a.body == QByteArray(4000, 'x'));
        CHECK(header(a, "Content-Length").isEmpty() && header(a, "Content-Encoding").isEmpty());
    }

    // 8 MB with a slow reader: the host pauses when asked and goes on when resumed.
    {
        void *h = newHandle();
        {
            QMutexLocker lock(&mutex);
            answers[h].pauses = true;
        }
        std::atomic<int> pauses = 0;
        QTimer reader;
        QObject::connect(&reader, &QTimer::timeout, [&] {
            bool resume = false;
            {
                QMutexLocker lock(&mutex);
                Answer &a = answers[h];
                a.consumed = std::min<qint64>(a.body.size(), a.consumed + 256 * 1024);
                if (a.paused && a.body.size() - a.consumed < 256 * 1024) {
                    a.paused = false;
                    resume = true;
                }
            }
            if (resume) {
                ++pauses;
                host->resume(h);
            }
        });
        reader.start(2);
        const QByteArray json = request("GET", server.url("/big"));
        host->start(h, json.constData(), nullptr, 0);
        CHECK(waitFor([h] { return answer(h).ends > 0; }, 30000));
        reader.stop();
        const Answer a = answer(h);
        CHECK(a.error.isEmpty() && a.body.size() == BigSize);
        int wrong = 0;
        for (qint64 i = 0; i < a.body.size(); ++i) wrong += a.body[i] != big(i);
        CHECK(wrong == 0);
        CHECK(pauses > 0);
        // Never more than our limit plus one read past it.
        CHECK(a.mostAhead <= PauseAbove + 64 * 1024);
    }

    // No server there: a network error, no head.
    {
        QTcpServer closed;
        closed.listen(QHostAddress::LocalHost);
        const QByteArray url = "http://127.0.0.1:" + QByteArray::number(closed.serverPort()) + "/";
        closed.close();
        const Answer a = answer(fetch(host, request("GET", url)));
        CHECK(a.heads == 0 && a.ends == 1 && a.error.value("kind") == "network");
    }

    // Cut short partway through the body: a network error after the head.
    {
        const Answer a = answer(fetch(host, request("GET", server.url("/broken"))));
        CHECK(a.heads == 1 && a.ends == 1 && a.error.value("kind") == "network");
    }

    // No answer: a timeout, before the head and partway through the body.
    {
        QElapsedTimer took;
        took.start();
        const Answer a = answer(fetch(host, request("GET", server.url("/slow"), {}, 200, 200)));
        CHECK(a.heads == 0 && a.ends == 1 && a.error.value("kind") == "timeout");
        CHECK(took.elapsed() >= 350 && took.elapsed() < 3000);
        const Answer b = answer(fetch(host, request("GET", server.url("/stalls"), {}, 0, 300)));
        CHECK(b.heads == 1 && b.body == "abc" && b.ends == 1 && b.error.value("kind") == "timeout");
    }

    // Cancelled by the core: ended, once, as cancelled; a later cancel or resume is ignored.
    {
        void *h = newHandle();
        const QByteArray json = request("GET", server.url("/slow"));
        host->start(h, json.constData(), nullptr, 0);
        waitFor([&server] { return server.last.path == "/slow"; });
        host->cancel(h);
        CHECK(waitFor([h] { return answer(h).ends > 0; }));
        host->cancel(h);
        host->resume(h);
        waitFor([] { return false; }, 100);
        const Answer a = answer(h);
        CHECK(a.ends == 1 && a.error.value("kind") == "cancelled");
    }

    if (failures) std::fprintf(stderr, "%d failed\n", failures);
    else std::printf("http_host_test: all passed\n");
    // Stops the host's thread, as quitting the app does (no exec() here to send it).
    QMetaObject::invokeMethod(&app, "aboutToQuit", Qt::DirectConnection);
    return failures ? 1 : 0;
}
