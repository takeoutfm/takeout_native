# Podcasts — design

Requirements: [../requirements/podcasts.md](../requirements/podcasts.md).

- `:shared` `podcast/TakeoutPodcastRepository`: series (`ps-` ids), episodes (`pe-` ids),
  subscribe, episode progress through `TakeoutProgress` (keyed by the episode's GUID, which is
  also the `QueueItem.etag` of a playing episode); `playedMark` gives the offset to report for
  "mark played". Subscriptions (`subscribedSeries`) and progress (`TakeoutProgress.offsets`)
  are shared state the pages follow ([decision 017](../decisions/017-shared-state-flows.md)):
  `PodcastDetails.following` applies them to loaded details; Linux watches
  `podcastSubscriptions` and `episodeProgress`.
- Android (phone, watch, TV): episodes play in `TakeoutMusicService` as `QueueItem(podcast =
  true)`; `:android-media` `PodcastProgress` saves their position every 10 s while playing, on
  pause and on moving on, and reports complete at the end; `ListenTracker` skips them.
- Mac/iOS: `AudioPlayer` saves episodes' progress (an episode's first save comes 10 s in);
  `SwiftCalls` has mark played.
- Linux, tofm: `Player` (`saveEpisode`, and `reportProgress` at the end); the `markPlayed`
  call.
