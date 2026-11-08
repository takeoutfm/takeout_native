#pragma once

#include <QByteArray>
#include <QHash>
#include <QObject>

class QNetworkAccessManager;
class QNetworkReply;
class QThread;
class QTimer;

// The shared core's HTTP, done by Qt Network (PLAN.md L8): libtakeout.so hands each request to
// the host through takeout_set_http (takeout.h), and this answers it with
// takeout_http_head / _data / _end. Qt's TLS is the system's OpenSSL, kept up to date by the
// distribution, and the system's proxy settings apply. Requests run on a thread of their own,
// so a busy UI never stalls them. Redirects are never followed (the core decides), and there's
// no cache and no cookies.
class HttpHost : public QObject
{
    Q_OBJECT

public:
    /** Where answers go: libtakeout.so's takeout_http_* by default; tests record them. */
    struct Sink {
        void (*head)(void *handle, int status, const char *headersJson);
        int (*data)(void *handle, const void *bytes, size_t length);
        void (*end)(void *handle, const char *errorJson);
    };

    /**
     * Starts the HTTP thread and registers it with the core (takeout_set_http); once a
     * process, before takeout_init. Needs a QCoreApplication; stops when it quits.
     */
    static void install();

    /** A host on its own thread answering to [sink] (install() uses the core's). */
    static HttpHost *create(const Sink &sink);

    /** The C API's start, cancel and resume: from any thread. [body] is copied. */
    void start(void *handle, const char *requestJson, const void *body, size_t length);
    void cancel(void *handle);
    void resume(void *handle);

    /** How much Qt reads ahead of a paused request before it stops reading the socket. */
    static constexpr qint64 ReadBuffer = 256 * 1024;

private:
    struct Request;

    explicit HttpHost(const Sink &sink);
    void startNow(void *handle, const QByteArray &json, const QByteArray &body);
    void sendHead(Request *request);
    void drain(Request *request);
    void finish(Request *request);
    void end(Request *request, const QByteArray &errorJson);
    void restartTimer(Request *request);

    Sink m_sink;
    QNetworkAccessManager *m_network = nullptr;
    QHash<void *, Request *> m_requests;
};
