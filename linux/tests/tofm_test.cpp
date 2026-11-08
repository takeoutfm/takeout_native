// tofm's screens rendered off-screen (FTXUI's Screen) and compared as text, and its plain
// helpers. No server: the shared core isn't started, so the screens show what they're given.

#include "CoverCells.h"
#include "Player.h"
#include "Requests.h"
#include "TakeoutCore.h"
#include "Text.h"
#include "Tofm.h"

#include <QCoreApplication>
#include <clocale>
#include <cstdio>
#include <ftxui/dom/elements.hpp>
#include <ftxui/screen/screen.hpp>
#include <string>
#include <vector>

using namespace ftxui;

namespace {
int failures = 0;

#define CHECK(condition) \
    do { \
        if (!(condition)) { \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
            ++failures; \
        } \
    } while (false)

/** [element] drawn [width] × [height], as lines of text (styles dropped, ends trimmed). */
std::vector<std::string> lines(const Element &element, int width, int height)
{
    Screen screen(width, height);
    Render(screen, element);
    std::vector<std::string> out;
    for (int y = 0; y < height; ++y) {
        std::string line;
        for (int x = 0; x < width; ++x) line += screen.PixelAt(x, y).character;
        while (!line.empty() && line.back() == ' ') line.pop_back();
        out.push_back(line);
    }
    return out;
}

void checkLines(const std::vector<std::string> &got, const std::vector<std::string> &want, int line)
{
    if (got == want) return;
    std::fprintf(stderr, "tofm_test.cpp:%d: the screen differs. Got:\n", line);
    for (const std::string &l : got) std::fprintf(stderr, "  |%s|\n", l.c_str());
    std::fprintf(stderr, "Wanted:\n");
    for (const std::string &l : want) std::fprintf(stderr, "  |%s|\n", l.c_str());
    ++failures;
}

bool contains(const std::vector<std::string> &got, const std::string &text)
{
    for (const std::string &line : got)
        if (line.find(text) != std::string::npos) return true;
    return false;
}
}

class TofmTest
{
public:
    explicit TofmTest(Tofm &tofm)
        : t(tofm)
    {
        t.m_size = {80, 24};
    }

    /** A page with a heading, actions, items (detail, right column, a long title) and a note. */
    Element page(int height, int selected)
    {
        Tofm::Page p;
        p.title = QStringLiteral("At War With the Mystics");
        p.subtitle = QStringLiteral("The Flaming Lips · 2006 · Album");
        p.rows.push_back({Tofm::Row::Action, QStringLiteral("▶ Play")});
        p.rows.push_back({Tofm::Row::Header, QStringLiteral("Tracks")});
        p.rows.push_back({Tofm::Row::Item, QStringLiteral(" 1  Free Radicals"), QStringLiteral("The Flaming Lips"), QStringLiteral("3:41")});
        p.rows.push_back({Tofm::Row::Item, QStringLiteral(" 2  My Cosmic Autumn Rebellion (The Inner Life as Blazing Shield of Defiance)"),
                          QStringLiteral("The Flaming Lips")});
        p.rows.push_back({Tofm::Row::Note, QStringLiteral("A note, not selectable.")});
        p.selected = selected;
        return t.renderPage(p, height);
    }

    /** The visible rows of a page filtered by [filter] (headings and actions drop out). */
    std::vector<QString> filtered(const QString &filter)
    {
        Tofm::Page p;
        p.rows.push_back({Tofm::Row::Header, QStringLiteral("Artists")});
        p.rows.push_back({Tofm::Row::Item, QStringLiteral("Beck"), QStringLiteral("alternative rock")});
        p.rows.push_back({Tofm::Row::Item, QStringLiteral("The Flaming Lips"), QStringLiteral("alternative rock")});
        p.rows.push_back({Tofm::Row::Item, QStringLiteral("Pavement"), QStringLiteral("indie rock")});
        p.filter = filter;
        std::vector<QString> titles;
        for (int i : p.visibleRows()) titles.push_back(p.rows[i].title);
        return titles;
    }

    Element code(const QString &code, int seconds, bool expired)
    {
        t.m_code = code;
        t.m_link = QStringLiteral("https://takeout.example/link");
        t.m_expiresAt = QDateTime::currentDateTimeUtc().addSecs(seconds);
        t.m_expired = expired;
        return t.renderCode();
    }

    Element help() { return t.renderHelp(); }

    std::vector<QString> settingsRows()
    {
        std::vector<QString> titles;
        for (const auto &row : t.settingsPage()->rows) titles.push_back(row.title);
        return titles;
    }

    /** The title as shown [elapsedMs] after the scrolling started. */
    QString marquee(const QString &text, int width, qint64 elapsedMs)
    {
        t.m_marqueeStart = QDateTime::currentDateTimeUtc().addMSecs(-elapsedMs);
        return t.marquee(text, width);
    }

    Element bar() { return t.renderBar(); }

private:
    Tofm &t;
};

int main(int argc, char *argv[])
{
    QCoreApplication app(argc, argv);
    QCoreApplication::setApplicationVersion(QStringLiteral("9.9"));
    // libmpv (the Player) needs C number formatting, as tofm's main() sets.
    std::setlocale(LC_NUMERIC, "C");

    // ---- Plain helpers ----
    CHECK(clock(0) == "0:00");
    CHECK(clock(75) == "1:15");
    CHECK(clock(3725) == "1:02:05");
    CHECK(clock(-5) == "0:00");

    auto play = [](const QStringList &args) {
        const auto request = requests::parse(args);
        return request ? request->kind + "|" + request->words : QStringLiteral("none");
    };
    CHECK(play({"artist", "Beck"}) == "artist|Beck");
    CHECK(play({"radio", "Beck"}) == "radio|Beck");
    CHECK(play({"album", "Yoshimi", "Battles", "the", "Pink", "Robots"}) == "album|Yoshimi Battles the Pink Robots");
    CHECK(play({"song", "Loser"}) == "song|Loser");
    CHECK(play({"track", "Loser"}) == "song|Loser");
    CHECK(play({"odelay"}) == "|odelay");
    CHECK(play({"guitar:jack"}) == "|guitar:jack");
    CHECK(play({"artist"}) == "|artist"); // a kind alone is just a word
    CHECK(play({}) == "none");

    // A 4×4 all-red picture: every cell red, as foreground or background.
    {
        std::vector<uint8_t> red(4 * 4 * 4);
        for (size_t i = 0; i < red.size(); i += 4) red[i] = 255, red[i + 1] = 0, red[i + 2] = 0, red[i + 3] = 255;
        const std::vector<CoverCell> cells = coverCells(red.data(), 4, 4, 16, 3, 2, 8, 16);
        CHECK(cells.size() == 6);
        for (const CoverCell &cell : cells) {
            const int shown = cell.glyph == " " ? cell.bg : (cell.fg >= 0 ? cell.fg : cell.bg);
            CHECK(shown >= 0 && ((shown >> 16) & 0xff) > 200 && ((shown >> 8) & 0xff) < 50 && (shown & 0xff) < 50);
        }
    }

    // ---- Screens ----
    TakeoutCore core; // not started: no server, no data
    Player player(&core, QStringLiteral("tofm-test"), QStringLiteral("tofm-test"));
    Tofm tofm(&core, &player);
    TofmTest test(tofm);

    // Item 2 selected: the long title is cut with "…" and its detail gives way; the
    // heading and note show as text.
    checkLines(lines(test.page(8, 2), 61, 9), {
        " At War With the Mystics",
        " The Flaming Lips · 2006 · Album",
        "   ▶ Play",
        " Tracks",
        "    1  Free Radicals  The Flaming Lips                  3:41",
        "    2  My Cosmic Autumn Rebellion (The Inner Life as Blazin…",
        "   A note, not selectable.",
        "",
        "",
    }, __LINE__);

    // Finding: only matching items, by title or detail.
    CHECK((test.filtered({}) == std::vector<QString>{"Artists", "Beck", "The Flaming Lips", "Pavement"}));
    CHECK((test.filtered("flaming") == std::vector<QString>{"The Flaming Lips"}));
    CHECK((test.filtered("ROCK") == std::vector<QString>{"Beck", "The Flaming Lips", "Pavement"}));
    CHECK((test.filtered("indie") == std::vector<QString>{"Pavement"}));
    CHECK(test.filtered("zzz").empty());

    // The sign-in code with its countdown, then expired.
    {
        const auto waiting = lines(test.code("AB12CD", 300, false), 60, 10);
        CHECK(contains(waiting, "https://takeout.example/link"));
        CHECK(contains(waiting, "│ AB12CD │"));
        CHECK(contains(waiting, "expires in 5:00") || contains(waiting, "expires in 4:59"));
        const auto expired = lines(test.code("AB12CD", 0, true), 60, 10);
        CHECK(contains(expired, "This code has expired."));
        CHECK(!contains(expired, "expires in"));
    }

    // Help lists the keys.
    {
        const auto help = lines(test.help(), 100, 30);
        CHECK(contains(help, "Moving around"));
        CHECK(contains(help, "back 10 s / ahead 30 s"));
        CHECK(contains(help, "remove a download"));
    }

    // Settings, signed out of everything: the rows there are.
    {
        const std::vector<QString> rows = test.settingsRows();
        auto has = [&rows](const QString &title) { return std::find(rows.begin(), rows.end(), title) != rows.end(); };
        CHECK(has("Track Activity: On"));
        CHECK(has("Connect…"));
        CHECK(has("Album art: On"));
        CHECK(has("Video player: mpv"));
        CHECK(has("tofm 9.9 — TakeoutFM in the terminal"));
    }

    // The scrolling title: short ones stay; long ones wait 1.5 s, then move a column per 150 ms.
    CHECK(test.marquee("Vein of Stars", 20, 5000) == "Vein of Stars");
    CHECK(test.marquee("Yoshimi Battles the Pink Robots", 10, 0) == "Yoshimi Ba");
    CHECK(test.marquee("Yoshimi Battles the Pink Robots", 10, 1400) == "Yoshimi Ba");
    CHECK(test.marquee("Yoshimi Battles the Pink Robots", 10, 1500 + 3 * 150 + 10) == "himi Battl");

    // Nothing playing.
    CHECK(contains(lines(test.bar(), 60, 3), "Nothing playing"));

    if (failures) std::fprintf(stderr, "%d failed\n", failures);
    else std::printf("tofm_test: all passed\n");
    return failures ? 1 : 0;
}
