// tofm: the Settings page, the ? help, and the one-line prompt at the bottom.

#include "Tofm.h"

#include "TakeoutCore.h"
#include "Text.h"

#include <QCoreApplication>

using namespace ftxui;

namespace {
QString onOff(bool on) { return on ? QStringLiteral("On") : QStringLiteral("Off"); }

const char *artName(int art)
{
    static const char *const Names[] = {"Auto", "Characters", "Off"};
    return Names[std::clamp(art, 0, 2)];
}
}

Tofm::Art Tofm::art() const
{
    const QString value = m_prefs.value("art", "Auto").toString();
    for (int i = 0; i < 3; ++i)
        if (value == artName(i)) return Art(i);
    return Art::Auto;
}

bool Tofm::barCover() const
{
    // Off over SSH by default: every cover crosses the link.
    return art() != Art::Off && m_prefs.value("barCover", qEnvironmentVariableIsEmpty("SSH_CONNECTION")).toBool();
}

void Tofm::refreshSettings()
{
    if (m_section != Section::Settings || m_stack.size() != 1) return;
    const int selected = m_stack[0]->selected;
    m_stack[0] = settingsPage();
    m_stack[0]->selected = selected;
    redraw();
}

Tofm::PagePtr Tofm::settingsPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Settings");
    auto &rows = page->rows;
    const QVariantMap settings = m_core->settings();

    rows.push_back({Row::Header, QStringLiteral("Account")});
    rows.push_back({Row::Note, QStringLiteral("Server       ") + m_core->endpoint()});
    if (!m_core->user().isEmpty()) rows.push_back({Row::Note, QStringLiteral("Signed in as ") + m_core->user()});
    rows.push_back({Row::Action, QStringLiteral("Sign out…"), {}, {}, {}, [this] {
        ask({QStringLiteral("Sign out of %1?").arg(m_core->endpoint()), {}, false, true,
             [this](const QString &) { m_core->call("signOut"); }});
    }, {}});

    rows.push_back({Row::Header, QStringLiteral("Music")});
    const bool reportPlays = settings.value("reportPlays", true).toBool();
    rows.push_back({Row::Action, QStringLiteral("Track Activity: ") + onOff(reportPlays),
                    QStringLiteral("tells your Takeout server what you play (half a track, or 4 minutes)"), {}, {},
                    [this, reportPlays] { m_core->call("setSettings", {{"reportPlays", !reportPlays}}); }, {}});

    rows.push_back({Row::Header, QStringLiteral("ListenBrainz")});
    const QString listenBrainz = m_core->listenBrainzUser();
    if (!listenBrainz.isEmpty()) {
        rows.push_back({Row::Note, QStringLiteral("Connected as ") + listenBrainz});
        const bool submit = settings.value("submitListens", true).toBool();
        rows.push_back({Row::Action, QStringLiteral("Submit listens: ") + onOff(submit),
                        QStringLiteral("what you play, radio songs included, to your ListenBrainz profile"), {}, {},
                        [this, submit] { m_core->call("setSettings", {{"submitListens", !submit}}); }, {}});
        rows.push_back({Row::Action, QStringLiteral("Disconnect"), {}, {}, {},
                        [this] { m_core->call("disconnectListenBrainz"); }, {}});
    } else {
        rows.push_back({Row::Action, QStringLiteral("Connect…"), QStringLiteral("with your user token from listenbrainz.org/settings"),
                        {}, {}, [this] {
            ask({QStringLiteral("ListenBrainz user token:"), {}, true, false, [this](const QString &token) {
                if (token.trimmed().isEmpty()) return;
                flash(QStringLiteral("Connecting…"));
                m_core->call("connectListenBrainz", {{"token", token.trimmed()}}, [this](const QVariant &user, const QVariant &) {
                    flash(user.toString().isEmpty() ? QStringLiteral("ListenBrainz didn't accept that token")
                                                    : QStringLiteral("Connected as ") + user.toString());
                });
            }});
        }, {}});
    }

    rows.push_back({Row::Header, QStringLiteral("Display")});
    // Covers are drawn with block characters, in every terminal (decided 2026-11-08: no kitty /
    // sixel images), so it's simply on or off.
    const bool covers = art() != Art::Off;
    rows.push_back({Row::Action, QStringLiteral("Album art: ") + onOff(covers),
                    QStringLiteral("covers drawn with coloured block characters"), {}, {}, [this, covers] {
        m_prefs.setValue("art", covers ? "Off" : "Auto");
        refreshSettings();
    }, {}});
    const bool bar = barCover();
    rows.push_back({Row::Action, QStringLiteral("Cover in the Now Playing bar: ") + onOff(bar),
                    QStringLiteral("off over SSH unless you turn it on"), {}, {}, [this, bar] {
        m_prefs.setValue("barCover", !bar);
        refreshSettings();
    }, {}});

    rows.push_back({Row::Header, QStringLiteral("Video")});
    const QString player = m_prefs.value("videoPlayer").toString().trimmed();
    rows.push_back({Row::Action, QStringLiteral("Video player: ") + (player.isEmpty() ? QStringLiteral("mpv") : player),
                    player.isEmpty() ? QStringLiteral("streams, resumes and saves progress")
                                     : QStringLiteral("downloads only, no progress (only mpv gets those)"),
                    {}, {}, [this, player] {
        ask({QStringLiteral("Video player ({file} for the file; empty for mpv):"), player, false, false, [this](const QString &value) {
            m_prefs.setValue("videoPlayer", value.trimmed());
            refreshSettings();
        }});
    }, {}});

    rows.push_back({Row::Header, QStringLiteral("About")});
    rows.push_back({Row::Note, QStringLiteral("tofm %1 — TakeoutFM in the terminal").arg(QCoreApplication::applicationVersion())});
    rows.push_back({Row::Note, QStringLiteral("GPL-3.0-or-later · https://takeout.fm")});
    rows.push_back({Row::Note, QStringLiteral("Log: ~/.local/state/tofm/tofm.log")});

    for (int i = 0; i < int(rows.size()); ++i) {
        if (rows[i].kind == Row::Action || rows[i].kind == Row::Item) {
            page->selected = i;
            break;
        }
    }
    return page;
}

// ---- Help ----

Element Tofm::renderHelp()
{
    auto group = [](const std::string &title, std::vector<std::pair<std::string, std::string>> keys) {
        Elements lines = {text(title) | bold | color(Color::Yellow)};
        for (const auto &[key, what] : keys)
            lines.push_back(hbox({text(key) | bold | size(WIDTH, EQUAL, 12), text(what)}));
        lines.push_back(text(""));
        return vbox(lines);
    };
    Element left = vbox({
        group("Moving around", {{"↑ ↓  j k", "move"}, {"PgUp PgDn", "a page"}, {"g G", "top, bottom"},
                                {"Enter → l", "open or play"}, {"← h Esc", "back"}, {"Tab", "the sidebar"},
                                {"/", "find in the list"}, {"s", "a new search (Search)"}}),
        group("Tracks", {{"a", "add to the end of the queue"}, {"n", "play next"}, {"f", "♥ favorite"}}),
        group("Downloads", {{"d", "download an episode"}, {"x", "remove a download"}, {"R", "retry a failed one"}}),
    });
    Element right = vbox({
        group("Playing", {{"space", "play / pause"}, {"< >", "previous / next"}, {"[ ]", "back 10 s / ahead 30 s"},
                          {"r", "repeat: off, all, one"}, {"p", "Now Playing and the queue"}}),
        group("tofm", {{"?", "this help"}, {"S", "sign out"}, {"q", "quit"}}),
        group("Also", {{"playerctl", "-p tofm play-pause, next…"}, {"Videos", "open in mpv"}}),
    });
    return vbox({
        text(" Keys") | bold,
        text(""),
        hbox({text(" "), left | size(WIDTH, EQUAL, 46), right}),
        text(" ? or Esc to close") | dim,
    });
}

// ---- Prompt ----

void Tofm::ask(const Prompt &prompt)
{
    m_prompt = std::make_unique<Prompt>(prompt);
    redraw();
}

bool Tofm::onPromptEvent(const Event &event)
{
    if (event == Event::Custom || event.is_mouse()) return false;
    if (m_prompt->yesNo) {
        // Taken out first: what it does may ask something else.
        const Prompt prompt = *m_prompt;
        m_prompt.reset();
        if (event == Event::Character('y') || event == Event::Character('Y')) prompt.done({});
        redraw();
        return true;
    }
    if (event == Event::Return) {
        const Prompt prompt = *m_prompt;
        m_prompt.reset();
        prompt.done(prompt.value);
    } else if (event == Event::Escape) {
        m_prompt.reset();
    } else if (event == Event::Backspace) {
        m_prompt->value.chop(1);
    } else if (event.is_character()) {
        m_prompt->value += QString::fromStdString(event.character());
    }
    redraw();
    return true;
}

Element Tofm::renderPrompt()
{
    if (m_prompt->yesNo) {
        return hbox({text(" " + str(m_prompt->label) + " ") | bold | color(Color::Yellow), text("y: yes · any other key: no") | dim});
    }
    const QString shown = m_prompt->secret ? QString(m_prompt->value.size(), QChar(u'•')) : m_prompt->value;
    return hbox({text(" " + str(m_prompt->label) + " ") | bold, text(str(shown)), text("▏") | blink, filler(),
                 text("Enter: ok · Esc: cancel ") | dim});
}
