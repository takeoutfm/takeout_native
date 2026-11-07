#include "Tofm.h"

#include "Cover.h"
#include "Player.h"
#include "SecretStore.h"
#include "TakeoutCore.h"
#include "Text.h"

#include <QCoreApplication>
#include <QProcess>
#include <QStandardPaths>

using namespace ftxui;

Tofm::Tofm(TakeoutCore *core, Player *player)
    : m_core(core)
    , m_player(player)
    , m_cover(std::make_unique<Cover>([this] { redraw(); }))
    , m_screen(ScreenInteractive::Fullscreen())
    , m_prefs(QStandardPaths::writableLocation(QStandardPaths::AppConfigLocation) + "/tofm.conf", QSettings::IniFormat)
{
    m_server = core->endpoint().isEmpty() ? std::string("https://takeout.fm") : str(core->endpoint());
    m_root = build();

    QObject::connect(core, &TakeoutCore::sessionChanged, core, [this] { sessionChanged(); });
    QObject::connect(core, &TakeoutCore::failed, core, [this](const QString &, const QVariantMap &error) {
        if (error.value("kind").toString() == "network") flash(QStringLiteral("Can't reach the server"));
    });
    QObject::connect(core, &TakeoutCore::favoriteTracksChanged, core, [this] { redraw(); });
    QObject::connect(core, &TakeoutCore::settingsChanged, core, [this] { refreshSettings(); });
    QObject::connect(core, &TakeoutCore::listenBrainzChanged, core, [this] { refreshSettings(); });
    QObject::connect(core, &TakeoutCore::recentlyPlayedChanged, core, [this] {
        if (m_section == Section::Recent && m_stack.size() == 1) m_stack[0] = recentPage();
        redraw();
    });
    for (auto signal : {&Player::itemsChanged, &Player::currentChanged, &Player::playingChanged,
                        &Player::positionChanged, &Player::durationChanged, &Player::repeatModeChanged})
        QObject::connect(player, signal, core, [this] { redraw(); });

    m_frames.setInterval(16);
    m_countdown.setInterval(1000);
    QObject::connect(&m_countdown, &QTimer::timeout, core, [this] {
        if (secondsLeft() == 0) {
            m_expired = true;
            m_countdown.stop();
        }
        redraw();
    });
    // Scrolling titles move a few times a second; nothing else needs frames.
    m_marqueeTick.setInterval(150);
    QObject::connect(&m_marqueeTick, &QTimer::timeout, core, [this] { redraw(); });
    m_marqueeTick.start();
    m_marqueeStart = QDateTime::currentDateTimeUtc();
    m_flashTimer.setSingleShot(true);
    m_flashTimer.setInterval(3000);
    QObject::connect(&m_flashTimer, &QTimer::timeout, core, [this] {
        m_flash.clear();
        redraw();
    });
}

Tofm::~Tofm()
{
    // Quitting with a video open: save where it got, and close it.
    if (m_video.process) {
        saveVideoProgress();
        m_video.process->terminate();
        m_video.process->waitForFinished(2000);
        videoClosed();
    }
}

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

void Tofm::flash(const QString &message)
{
    m_flash = message;
    m_flashTimer.start();
    redraw();
}

void Tofm::quit() { m_screen.Exit(); }

void Tofm::sessionChanged()
{
    if (!m_core->ready()) {
        m_screenState = Screen::Starting;
    } else if (m_core->signedIn()) {
        if (m_screenState != Screen::Browse) {
            m_screenState = Screen::Browse;
            m_countdown.stop();
            m_code.clear();
            m_core->call("refreshLibrary", {{"maxAgeMs", 60000}});
            showSection(Section::Recent);
        }
    } else {
        if (m_screenState == Screen::Browse) {
            // Signed out: nothing of the old account stays.
            m_player->stop();
            m_stack.clear();
            m_nowPlaying = false;
        }
        if (m_screenState != Screen::Code) m_screenState = Screen::SignIn;
        m_prompt.reset();
    }
    redraw();
}

bool Tofm::onEvent(const Event &event)
{
    if (event == Event::Custom) return false;
    switch (m_screenState) {
    case Screen::Starting:
        if (event == Event::Character('q')) quit();
        return true;
    case Screen::SignIn:
    case Screen::Code:
        return onSignInEvent(event);
    case Screen::Browse:
        return onBrowseEvent(event);
    }
    return false;
}

Element Tofm::render()
{
    Element body;
    switch (m_screenState) {
    case Screen::Starting: body = text("Starting…") | dim | center | flex; break;
    case Screen::SignIn: body = renderSignIn() | center | flex; break;
    case Screen::Code: body = renderCode() | center | flex; break;
    case Screen::Browse: body = renderBrowse() | flex; break;
    }
    Element right = text(m_core->signedIn() ? str(m_core->endpoint()) + " " : "") | dim;
    if (!m_flash.isEmpty()) right = text(str(m_flash) + " ") | color(Color::Yellow);
    return vbox({
        hbox({text(" tofm ") | bold | inverted, filler(), right}),
        body,
    });
}

// ---- Sign-in ----

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
            m_error = error.toMap().value("kind").toString() == "network"
                ? QStringLiteral("Can't reach %1").arg(QString::fromStdString(m_server))
                : QStringLiteral("That server didn't give a code");
            m_screenState = Screen::SignIn;
            return redraw();
        }
        const QVariantMap code = result.toMap();
        m_code = code.value("code").toString();
        m_link = code.value("link").toString();
        m_expiresAt = QDateTime::currentDateTimeUtc().addSecs(code.value("expiresIn").toInt());
        m_expired = false;
        m_screenState = Screen::Code;
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
    m_screenState = Screen::SignIn;
    redraw();
}

bool Tofm::onSignInEvent(const Event &event)
{
    if (m_screenState == Screen::SignIn) {
        if (event == Event::Escape) {
            quit();
            return true;
        }
        return false; // the server field
    }
    if (event == Event::Character('n')) requestCode();
    else if (event == Event::Escape) cancelCode();
    else if (event == Event::Character('q')) quit();
    return true;
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
    if (m_expired)
        lines.push_back(text("This code has expired.") | color(Color::Red) | hcenter);
    else
        lines.push_back(text("Waiting for approval · expires in " + str(clock(secondsLeft()))) | dim | hcenter);
    lines.push_back(hbox({keyHint("n", "new code"), keyHint("Esc", "back"), keyHint("q", "quit")}) | hcenter);
    return vbox(lines);
}
