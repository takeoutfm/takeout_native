#include "Tofm.h"

#include "SecretStore.h"
#include "TakeoutCore.h"

#include <QCoreApplication>

using namespace ftxui;

namespace {
std::string str(const QString &text) { return text.toStdString(); }

QString countdown(int seconds)
{
    return QStringLiteral("%1:%2").arg(seconds / 60).arg(seconds % 60, 2, 10, QLatin1Char('0'));
}

Element keyHint(const std::string &key, const std::string &what)
{
    return hbox({text(key) | bold, text(" " + what + "   ") | dim});
}
}

Tofm::Tofm(TakeoutCore *core)
    : m_core(core)
    , m_screen(ScreenInteractive::Fullscreen())
{
    m_server = core->endpoint().isEmpty() ? std::string("https://takeout.fm") : str(core->endpoint());
    m_root = build();

    QObject::connect(core, &TakeoutCore::sessionChanged, core, [this] { sessionChanged(); });
    QObject::connect(core, &TakeoutCore::failed, core, [this](const QString &, const QVariantMap &) { redraw(); });

    // FTXUI's loop, a frame at a time, from Qt's.
    m_frames.setInterval(16);
    m_countdown.setInterval(1000);
    QObject::connect(&m_countdown, &QTimer::timeout, core, [this] {
        if (secondsLeft() == 0) {
            m_expired = true;
            m_countdown.stop();
        }
        redraw();
    });
}

Tofm::~Tofm() = default;

int Tofm::run()
{
    Loop loop(&m_screen, m_root);
    QObject::connect(&m_frames, &QTimer::timeout, m_core, [&loop] {
        loop.RunOnce();
        if (loop.HasQuitted()) QCoreApplication::quit();
    });
    m_frames.start();
    sessionChanged();
    const int code = QCoreApplication::exec();
    m_frames.stop();
    return code;
}

Component Tofm::build()
{
    InputOption server;
    server.multiline = false;
    server.on_enter = [this] { requestCode(); };
    m_serverInput = Input(&m_server, "https://takeout.fm", server);

    auto page = Renderer(m_serverInput, [this] { return render(); });
    return CatchEvent(page, [this](const Event &event) { return onEvent(event); });
}

void Tofm::redraw() { m_screen.PostEvent(Event::Custom); }

void Tofm::sessionChanged()
{
    if (!m_core->ready()) {
        m_page = Page::Starting;
    } else if (m_core->signedIn()) {
        m_page = Page::Home;
        m_countdown.stop();
        m_code.clear();
    } else if (m_page != Page::Code) {
        m_page = Page::SignIn;
        m_confirmSignOut = false;
    }
    redraw();
}

int Tofm::secondsLeft() const
{
    if (!m_expiresAt.isValid()) return 0;
    const qint64 ms = QDateTime::currentDateTimeUtc().msecsTo(m_expiresAt);
    return ms <= 0 ? 0 : int((ms + 999) / 1000);
}

void Tofm::requestCode()
{
    if (m_requesting) return;
    m_requesting = true;
    m_error.clear();
    const int generation = ++m_codeGeneration;
    m_core->call("requestCode", {{"host", QString::fromStdString(m_server)}},
                 [this, generation](const QVariant &result, const QVariant &error) {
        m_requesting = false;
        if (generation != m_codeGeneration) return;
        if (error.isValid()) {
            const QVariantMap failure = error.toMap();
            m_error = failure.value("kind").toString() == "network"
                ? QStringLiteral("Can't reach %1").arg(QString::fromStdString(m_server))
                : QStringLiteral("That server didn't give a code");
            m_page = Page::SignIn;
            return redraw();
        }
        const QVariantMap code = result.toMap();
        m_code = code.value("code").toString();
        m_link = code.value("link").toString();
        m_expiresAt = QDateTime::currentDateTimeUtc().addSecs(code.value("expiresIn").toInt());
        m_expired = false;
        m_page = Page::Code;
        m_countdown.start();
        redraw();
        // True once approved (the session then changes to signed in); false when it expired.
        m_core->call("awaitCode", {}, [this, generation](const QVariant &signedIn, const QVariant &error) {
            if (generation != m_codeGeneration || error.isValid() || signedIn.toBool()) return;
            m_expired = true;
            m_countdown.stop();
            redraw();
        });
    });
    redraw();
}

void Tofm::cancelCode()
{
    ++m_codeGeneration;
    m_core->call("cancelCode");
    m_countdown.stop();
    m_code.clear();
    m_page = Page::SignIn;
    redraw();
}

bool Tofm::onEvent(const Event &event)
{
    if (event == Event::Custom) return false;
    switch (m_page) {
    case Page::SignIn:
        if (event == Event::Escape) {
            m_screen.Exit();
            return true;
        }
        return false; // the server field
    case Page::Code:
        if (event == Event::Character('n')) {
            requestCode();
            return true;
        }
        if (event == Event::Escape) {
            cancelCode();
            return true;
        }
        if (event == Event::Character('q')) {
            m_screen.Exit();
            return true;
        }
        return true;
    case Page::Home:
        if (m_confirmSignOut) {
            if (event == Event::Character('y')) m_core->call("signOut");
            m_confirmSignOut = false;
            redraw();
            return true;
        }
        if (event == Event::Character('s')) {
            m_confirmSignOut = true;
            return true;
        }
        if (event == Event::Character('q')) {
            m_screen.Exit();
            return true;
        }
        return true;
    case Page::Starting:
        if (event == Event::Character('q')) m_screen.Exit();
        return true;
    }
    return false;
}

Element Tofm::render()
{
    Element body;
    switch (m_page) {
    case Page::Starting: body = text("Starting…") | dim; break;
    case Page::SignIn: body = renderSignIn(); break;
    case Page::Code: body = renderCode(); break;
    case Page::Home: body = renderHome(); break;
    }
    return vbox({
        hbox({text(" tofm ") | bold | inverted, filler(),
              text(m_core->signedIn() ? str(m_core->endpoint()) + " " : "") | dim}),
        body | center | flex,
    });
}

Element Tofm::renderSignIn()
{
    Elements lines = {
        text("Sign in to your Takeout server") | bold | hcenter,
        text(""),
        hbox({text("Server: "), m_serverInput->Render() | size(WIDTH, GREATER_THAN, 40) | border}),
        text(""),
        hbox({keyHint("Enter", m_requesting ? "getting a code…" : "get a sign-in code"), keyHint("Esc", "quit")}) | hcenter,
    };
    if (!m_error.isEmpty()) lines.push_back(text(str(m_error)) | color(Color::Red) | hcenter);
    if (SecretStore::usingFallback())
        lines.push_back(paragraph("No keyring here: tofm keeps its sign-in in a file only you can read.") | dim);
    return vbox(lines) | size(WIDTH, LESS_THAN, 70);
}

Element Tofm::renderCode()
{
    Element code = text(" " + str(m_code) + " ") | bold;
    if (m_expired) code = code | strikethrough | dim;
    Elements lines = {
        text("On a signed-in device, open") | hcenter,
        text(str(m_link)) | underlined | hcenter,
        text("and enter this code:") | hcenter,
        text(""),
        code | border | hcenter,
        text(""),
    };
    if (m_expired) {
        lines.push_back(text("This code has expired.") | color(Color::Red) | hcenter);
        lines.push_back(hbox({keyHint("n", "new code"), keyHint("Esc", "back"), keyHint("q", "quit")}) | hcenter);
    } else {
        lines.push_back(text("Waiting for approval · expires in " + str(countdown(secondsLeft()))) | dim | hcenter);
        lines.push_back(hbox({keyHint("n", "new code"), keyHint("Esc", "back"), keyHint("q", "quit")}) | hcenter);
    }
    return vbox(lines);
}

Element Tofm::renderHome()
{
    const QString who = m_core->user().isEmpty() ? QString() : QStringLiteral(" as %1").arg(m_core->user());
    Elements lines = {
        text("Signed in to " + str(m_core->endpoint()) + str(who)) | hcenter,
        text(""),
        text("Browsing and playing music arrive in T2.") | dim | hcenter,
        text(""),
    };
    if (m_confirmSignOut)
        lines.push_back(text("Sign out? y: yes · any other key: no") | color(Color::Yellow) | hcenter);
    else
        lines.push_back(hbox({keyHint("s", "sign out"), keyHint("q", "quit")}) | hcenter);
    return vbox(lines);
}
