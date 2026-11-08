#pragma once

#include <QDateTime>
#include <QSettings>
#include <QString>
#include <QTimer>
#include <QVariant>
#include <ftxui/component/component.hpp>
#include <ftxui/component/loop.hpp>
#include <ftxui/component/screen_interactive.hpp>
#include <functional>
#include <memory>
#include <optional>
#include <vector>

class Cover;
class Player;
class QLocalSocket;
class QProcess;
class TakeoutCore;

// tofm's screens (FTXUI) over the shared core, on Qt's event loop: a timer runs FTXUI's
// RunOnce(), so terminal input, mpv events, D-Bus and the core's callbacks all arrive on one
// thread. State changes call redraw().
class Tofm
{
    // tests/tofm_test.cpp renders the screens.
    friend class TofmTest;

public:
    Tofm(TakeoutCore *core, Player *player);
    ~Tofm();

    /** Runs until the user quits (q, Ctrl-C); the exit code. */
    int run();

    /**
     * `tofm play …`: once signed in, searches for [words] and plays the match — an artist's
     * popular tracks or radio, an album, tracks, a station ([kind] "artist", "radio", "album",
     * "song", or "" for the best of them) — and shows Now Playing.
     */
    void playRequest(const QString &kind, const QString &words);
    /** `tofm --status`: "▶ Title · Artist" (⏸ when paused), or "" with nothing playing. */
    QString status() const;

private:
    enum class Screen { Starting, SignIn, Code, Browse };

    /** A line of a page: a heading, an action ("▶ Play"), or something to open or play. */
    struct Row {
        /** Note: a line of text (a description), not selectable. */
        enum Kind { Header, Action, Item, Note } kind = Item;
        QString title;
        QString detail;
        QString right;
        /** A track's ETag: ♥ shows and toggles it. */
        QString etag;
        /** Enter. */
        std::function<void()> activate;
        /** a / n: add a track at the end of the queue (true) or play it next. */
        std::function<void(bool last)> enqueue;
        /** d: download it (an episode). */
        std::function<void()> download;
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
        /**
         * A search page: the "filter" is the query, Enter runs this with it, and rows aren't
         * filtered by it.
         */
        std::function<void(const QString &)> search;
        /** A cover (album, artist) beside the title; "" for none. */
        QString coverUri;
        /** Rows to show: all, or those matching the filter (headings and actions hidden). */
        std::vector<int> visibleRows() const;
    };
    using PagePtr = std::shared_ptr<Page>;

    enum class Section {
        Recent, Search, RecentlyAdded, NewReleases, Artists, Favorites, Playlists, Radio, Podcasts, Movies, Shows, Downloads, Settings,
    };

    /** Album art: as the terminal allows (block characters for now), always characters, or none. */
    enum class Art { Auto, Characters, Off };

    /** A one-line question at the bottom (the ListenBrainz token, "sign out?"). */
    struct Prompt {
        QString label;
        QString value;
        bool secret = false;
        /** y / n instead of typing. */
        bool yesNo = false;
        std::function<void(const QString &)> done;
    };

    // ---- Shell (Tofm.cpp) ----
    ftxui::Component build();
    ftxui::Element render();
    bool onEvent(const ftxui::Event &event);
    void sessionChanged();
    void redraw();
    void flash(const QString &message);
    void quit();
    /** The terminal's size (a test sets its own). */
    int columns() const { return m_size.first ? m_size.first : m_screen.dimx(); }
    int rows() const { return m_size.second ? m_size.second : m_screen.dimy(); }

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
    /** The sidebar's sections, those the server's library has. */
    std::vector<Section> sections() const;

    // ---- Search, playlists, radio, podcasts, video (Library.cpp) ----
    PagePtr searchPage();
    void runSearch(const PagePtr &page, const QString &query);
    PagePtr playlistsPage();
    PagePtr playlistPage(const QVariantMap &playlist);
    PagePtr radioPage();
    PagePtr podcastsPage();
    PagePtr podcastPage(const QVariantMap &series);
    PagePtr videoListPage(const QString &title, const QString &call);
    PagePtr videoPage(const QVariantMap &card);
    Row videoRow(const QVariantMap &card);
    Row stationRow(const QVariantMap &station);

    // ---- Downloads (Downloads.cpp) ----
    PagePtr downloadsPage();
    void refreshDownloads();
    /** Downloads the queue [call] returns: all of it, or its item [only]. */
    void downloadCall(const QString &call, const QVariantMap &args, int only = -1);
    /** "↓ Download" or "✕ Remove download" for [etags] (an album's tracks, a playlist's items). */
    Row downloadRow(const QStringList &etags, std::function<void()> download);
    /** ✓, a percentage, or "" for [etag]'s download. */
    QString downloadMark(const QString &etag) const;
    void removeDownloads(const QStringList &etags, const QString &what);

    // ---- Settings and help (Settings.cpp) ----
    PagePtr settingsPage();
    void refreshSettings();
    ftxui::Element renderHelp();
    void ask(const Prompt &prompt);
    bool onPromptEvent(const ftxui::Event &event);
    ftxui::Element renderPrompt();
    Art art() const;
    bool barCover() const;

    // ---- Video in an external player (Video.cpp) ----
    /** Plays movie / series / episode [id] in mpv, from where it was left unless [startOver]. */
    void playVideo(const QString &id, bool startOver);
    void videoEvent(const QByteArray &line);
    void saveVideoProgress();
    void videoClosed();
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
    std::pair<int, int> m_size{0, 0};
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
    bool m_help = false;
    /** A `tofm play …` waiting for the sign-in. */
    std::optional<std::pair<QString, QString>> m_pendingPlay;
    std::unique_ptr<Prompt> m_prompt;
    /** tofm's own settings (album art): ~/.config/tofm/tofm.conf. */
    QSettings m_prefs;

    // The video playing in mpv: its process, control socket, the token file, and where it got.
    struct Video {
        QProcess *process = nullptr;
        QLocalSocket *socket = nullptr;
        QString title;
        QString etag;
        QString configFile;
        QString socketPath;
        double position = 0;
        double duration = 0;
        QByteArray pending;
    } m_video;
    QTimer m_videoSave;
    QTimer m_videoConnect;
};
