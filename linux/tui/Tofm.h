#pragma once

#include <QDateTime>
#include <QString>
#include <QTimer>
#include <QVariant>
#include <ftxui/component/component.hpp>
#include <ftxui/component/loop.hpp>
#include <ftxui/component/screen_interactive.hpp>
#include <functional>
#include <memory>
#include <vector>

class Cover;
class Player;
class TakeoutCore;

// tofm's screens (FTXUI) over the shared core, on Qt's event loop: a timer runs FTXUI's
// RunOnce(), so terminal input, mpv events, D-Bus and the core's callbacks all arrive on one
// thread. State changes call redraw().
class Tofm
{
public:
    Tofm(TakeoutCore *core, Player *player);
    ~Tofm();

    /** Runs until the user quits (q, Ctrl-C); the exit code. */
    int run();

private:
    enum class Screen { Starting, SignIn, Code, Browse };

    /** A line of a page: a heading, an action ("▶ Play"), or something to open or play. */
    struct Row {
        enum Kind { Header, Action, Item } kind = Item;
        QString title;
        QString detail;
        QString right;
        /** A track's ETag: ♥ shows and toggles it. */
        QString etag;
        /** Enter. */
        std::function<void()> activate;
        /** a / n: add a track at the end of the queue (true) or play it next. */
        std::function<void(bool last)> enqueue;
    };

    /** A page of rows: a sidebar section, or what it opened (an artist, an album). */
    struct Page {
        QString title;
        QString subtitle;
        std::vector<Row> rows;
        bool loading = false;
        QString error;
        /** Index into visibleRows(), and the first one shown. */
        int selected = 0;
        int scroll = 0;
        QString filter;
        bool filtering = false;
        /** Rows to show: all, or those matching the filter (headings and actions hidden). */
        std::vector<int> visibleRows() const;
    };
    using PagePtr = std::shared_ptr<Page>;

    enum class Section { Recent, RecentlyAdded, NewReleases, Artists, Favorites };

    // ---- Shell (Tofm.cpp) ----
    ftxui::Component build();
    ftxui::Element render();
    bool onEvent(const ftxui::Event &event);
    void sessionChanged();
    void redraw();
    void flash(const QString &message);
    void quit();

    // ---- Sign-in (Tofm.cpp) ----
    ftxui::Element renderSignIn();
    ftxui::Element renderCode();
    bool onSignInEvent(const ftxui::Event &event);
    void requestCode();
    void cancelCode();
    int secondsLeft() const;

    // ---- Browsing (Browse.cpp) ----
    ftxui::Element renderBrowse();
    ftxui::Element renderSidebar();
    ftxui::Element renderPage(const Page &page, int height);
    bool onBrowseEvent(const ftxui::Event &event);
    bool onPageEvent(Page &page, const ftxui::Event &event);
    void showSection(Section section);
    void push(const PagePtr &page);
    void back();
    Page *currentPage();
    PagePtr sectionPage(Section section);
    PagePtr recentPage();
    PagePtr albumListPage(const QString &title, const QString &call);
    PagePtr artistsPage();
    PagePtr favoritesPage();
    PagePtr artistPage(const QVariantMap &artist);
    PagePtr albumPage(const QVariantMap &album);
    Row albumRow(const QVariantMap &album, bool showArtist);
    Row artistRow(const QVariantMap &artist);
    Row trackRow(const QVariantMap &track, const QString &detail, std::function<void()> play);
    /** Loads [call]'s result into [page] with [fill], showing loading / failure meanwhile. */
    void load(const PagePtr &page, const QString &call, const QVariantMap &args,
              std::function<void(Page &, const QVariant &)> fill);
    void playCall(const QString &call, const QVariantMap &args, bool shuffled = false);
    void enqueueTrack(const QString &trackId, bool last);
    void toggleFavorite(const QString &etag);
    int pageHeight() const;

    // ---- Now Playing (NowPlaying.cpp) ----
    ftxui::Element renderBar();
    ftxui::Element renderNowPlaying(int height);
    bool onPlayerKey(const ftxui::Event &event);
    bool onNowPlayingEvent(const ftxui::Event &event);
    /** [text] scrolled to fit [width] columns when it's too long (as the other apps' titles). */
    QString marquee(const QString &text, int width) const;

    TakeoutCore *m_core;
    Player *m_player;
    std::unique_ptr<Cover> m_cover;
    ftxui::ScreenInteractive m_screen;
    ftxui::Component m_root;
    ftxui::Component m_serverInput;
    QTimer m_frames;
    QTimer m_countdown;
    QTimer m_marqueeTick;
    QDateTime m_marqueeStart;

    Screen m_screenState = Screen::Starting;
    QString m_flash;
    QTimer m_flashTimer;

    // Sign-in.
    std::string m_server;
    QString m_error;
    QString m_code;
    QString m_link;
    QDateTime m_expiresAt;
    bool m_expired = false;
    bool m_requesting = false;
    int m_codeGeneration = 0;

    // Browsing.
    Section m_section = Section::Recent;
    bool m_sidebarFocused = false;
    std::vector<PagePtr> m_stack;
    bool m_nowPlaying = false;
    int m_queueSelected = 0;
    int m_queueScroll = 0;
    bool m_confirmSignOut = false;
};
