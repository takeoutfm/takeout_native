# Video audio and subtitle track choices

Which audio and subtitle tracks a movie, episode or live program plays with, and how a
viewer's choices carry over. Design: [../design/video-tracks.md](../design/video-tracks.md).
Decision: [003](../decisions/003-track-choices-by-language.md).

## Tracks

- **TRACKS-1** Audio and subtitles are the file's **embedded tracks only**; no server-side or
  downloaded subtitles.
- **TRACKS-2** Audio tracks are labelled by the shared rule: format and channels over language
  and name ("Dolby Digital · Surround 5.1" over "English · Commentary"), as files often name
  every track alike.
- **TRACKS-3** Subtitle tracks are labelled by language, name, Forced / SDH and format
  ("English · SDH · PGS"); the subtitle picker starts with *Off*.
- **TRACKS-4** Tracks the device can't decode are listed but can't be picked (where the player
  says so).

## Choosing

- **TRACKS-5** Each new title (or live program) gets the **best audio track** by the shared
  ranking, preferring the language of the audio track the viewer last picked by hand, else
  the device's language.
- **TRACKS-6** Each new title gets the subtitles in the **language the viewer last picked**:
  - never picked: the title's own default (forced subtitles show);
  - picked *Off*: none;
  - picked a language: the first track in it; if the title has none, its own default.
- **TRACKS-7** Only the **viewer's picks** are remembered, never the app's own choices (the best
  audio track, a remembered subtitle language applied): the best track for a Japanese-only
  film must not make Japanese the preferred language.
- **TRACKS-8** A pick of a track with no language changes nothing remembered.
- **TRACKS-9** Choices are remembered by **language only**, not format or flags (decision 003).
- **TRACKS-10** Choices are **saved on the device**, and **shared by the video and live
  players**: a pick in either applies to the next title in both.
- **TRACKS-11** When the player reopens the title it's playing (a fresh signed URL after a long
  pause), the tracks playing are kept.
- **TRACKS-12** A choice made in the player applies at once to what's playing.

## Status

| Rule | Phone | TV | Mac/iOS | Linux | tofm | Watch |
|---|---|---|---|---|---|---|
| TRACKS-1 embedded only | ✅ | ✅ | ✅ | ✅ | ✅ | — no video |
| TRACKS-2 audio labels | ⚠️ Media3's own menu names them | ✅ | ✅ | ✅ | — mpv's | — |
| TRACKS-3 subtitle labels | ⚠️ Media3's | ✅ | ⚠️ VLC's names | ✅ | — mpv's | — |
| TRACKS-5 best audio | ✅ | ✅ | ✅ | ✅ | — mpv picks | — |
| TRACKS-6 subtitle language | ✅ | ✅ | ✅ | ✅ | — mpv picks | — |
| TRACKS-7 only the viewer's picks | ✅ | ✅ (own pickers) | ✅ | ✅ | — | — |
| TRACKS-10 saved, both players | ✅ | ✅ | ✅ | ✅ | — | — |
| TRACKS-11 reopening keeps tracks | — the player follows URLs itself | — | — | ✅ | — | — |

Known gaps: the Linux app compares subtitle languages as the file gives them ("eng" and "en"
don't match), where the others normalise them; tofm hands video to mpv, which chooses for
itself.
