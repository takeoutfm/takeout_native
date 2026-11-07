#pragma once

#include <QDateTime>
#include <QString>
#include <QTimer>
#include <ftxui/component/component.hpp>
#include <ftxui/component/loop.hpp>
#include <ftxui/component/screen_interactive.hpp>
#include <memory>

class TakeoutCore;

// tofm's screens (FTXUI) over the shared core, on Qt's event loop: a timer runs FTXUI's
// RunOnce(), so terminal input, mpv events, D-Bus and the core's callbacks all arrive on one
// thread. State changes call redraw().
class Tofm
{
public:
    explicit Tofm(TakeoutCore *core);
    ~Tofm();

    /** Runs until the user quits (q, Ctrl-C); the exit code. */
    int run();

private:
    enum class Page { Starting, SignIn, Code, Home };

    ftxui::Component build();
    ftxui::Element render();
    ftxui::Element renderSignIn();
    ftxui::Element renderCode();
    ftxui::Element renderHome();
    bool onEvent(const ftxui::Event &event);

    void sessionChanged();
    void requestCode();
    void cancelCode();
    void redraw();
    int secondsLeft() const;

    TakeoutCore *m_core;
    ftxui::ScreenInteractive m_screen;
    ftxui::Component m_root;
    ftxui::Component m_serverInput;
    QTimer m_frames;
    QTimer m_countdown;

    Page m_page = Page::Starting;
    std::string m_server;
    QString m_error;
    // The code being shown, and when it stops being valid (this computer's clock).
    QString m_code;
    QString m_link;
    QDateTime m_expiresAt;
    bool m_expired = false;
    bool m_requesting = false;
    /** Bumped for each code, so a cancelled wait's answer is ignored. */
    int m_codeGeneration = 0;
    bool m_confirmSignOut = false;
};
