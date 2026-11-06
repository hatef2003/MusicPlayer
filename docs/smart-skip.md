# Smart Skip

Smart Skip learns the parts of each song you habitually skip — a long intro, a
drawn-out outro — and starts skipping them for you. There is nothing to set up:
skip the way you normally do, and after the second time the player picks it up.

It works **per song**, learns only from what *you* do, and always errs on the
side of playing more of the song rather than less.

---

## For listeners

### Intros

If you jump forward right after a song starts, and you land in roughly the same
place twice, the song will start at that point from then on.

> Example: on two plays of a track you drag the seek bar from 0:02 to 0:38, then
> from 0:04 to 0:41. On the next play the track starts at **0:38** and a toast
> shows `⏭ Smart Skip: intro skipped · seek to 0:00 to keep it`.

### Outros

If you press **Next** late in a song at roughly the same point twice, the player
will move on at that point automatically.

> Example: you press Next at 3:52 and later at 3:55 on a 4:30 track. Next time
> the player moves on at **3:55** and shows `⏭ Smart Skip: outro skipped`.

Next works from anywhere: the Now Playing screen, the notification, the lock
screen, and headset or Bluetooth buttons.

### Undo

Changed your mind about an intro? Right after Smart Skip jumps past it, **seek
back to 0:00**. That song's intro is forgotten and will play normally from then
on (`Smart Skip: this intro will play from now on`).

To reset everything, go to **Settings → Smart Skip → Forget learned skips**.

### Seeing what was learned

Open a song's **Track Details** (long-press or `⋮` → Track Details). Songs with a
learned skip show:

| Field | Meaning |
|---|---|
| `SMART SKIP INTRO` | The song starts at this position |
| `SMART SKIP OUTRO` | The player moves on at this position |

Songs with nothing learned don't show these fields.

### Turning it off

**Settings → Smart Skip** has an on/off switch. It is **on** by default. While it
is off, the player neither learns nor skips; your learned skips are kept and
come back if you turn it on again.

---

## Exact rules

These thresholds are defined as constants in
[`SmartSkipManager`](../app/src/main/java/com/example/musicplayer/SmartSkipManager.java).

### What counts as an intro skip

A seek is recorded as an intro skip only when **all** of these hold:

| Rule | Value |
|---|---|
| The seek starts within this time of the track starting | 15 s (`INTRO_SEEK_FROM_MAX_MS`) |
| It jumps forward at least | 5 s (`INTRO_MIN_JUMP_MS`) |
| It lands no later than | 2:00 (`INTRO_TARGET_MAX_MS`) |
| It lands before the middle of the track | half the duration |
| Smart Skip hasn't already skipped this track's intro on this play | — |

### What counts as an outro skip

A **Next** press is recorded as an outro skip only when:

| Rule | Value |
|---|---|
| At least this much of the track has played | 60 % (`OUTRO_MIN_FRACTION`) |
| At least this much of the track is left | 5 s (`OUTRO_MIN_REMAINING_MS`) |

### When a skip is learned

Each song keeps its **last 3** intro and outro observations. A skip is applied
only when the **two most recent** observations are within **5 s** of each other
(`AGREEMENT_MS`). If you skip somewhere different later, the two latest no
longer agree and Smart Skip stops skipping that song until they agree again.

When they agree, the conservative point is used:

- **Intro** → the *earlier* of the two (skip less).
- **Outro** → the *later* of the two (play more).

### What it ignores

- Seeks and track changes made by the app itself, including Smart Skip's own
  jumps, a track ending normally, repeat, and the queue advancing.
- Resuming a saved position after the app was closed or killed. A restored
  position always wins over a learned intro.
- Previous, and seeking backwards (except the 0:00 undo above).

### How an automatic outro behaves

Reaching a learned outro is treated exactly like the track ending on its own:

- the play is counted in [play stats](#interaction-with-play-stats),
- **Repeat one** replays the track, **Repeat all** wraps to the start of the
  queue,
- at the end of the queue with repeat off, playback stops.

---

## Interaction with play stats

Smart Skip and the **Most played / Recently played** auto-playlists are
independent, but they touch at two points:

- An automatic outro counts as a completed play, just like a natural ending.
- A skipped intro is not listening time. A play is counted once you've listened
  to half the track (capped at 4 minutes), so heavily skipped songs need slightly
  more actual listening before they count.

---

## Privacy and storage

Everything stays on the device. Nothing is sent anywhere.

| Data | Location |
|---|---|
| Learned positions (milliseconds, per MediaStore audio ID) | SharedPreferences file `smart_skip`, key `data_json` |
| On/off switch | Same file, key `enabled` |

Stored format (one entry per song ID; `i` = intro observations, `o` = outro
observations, oldest first):

```json
{
  "1042": { "i": [38120, 41030], "o": [] },
  "2210": { "i": [],             "o": [232400, 235100] }
}
```

Uninstalling the app or clearing its storage removes all of it, and so does
**Forget learned skips**.

---

## For developers

| File | Role |
|---|---|
| `SmartSkipManager.java` | Stores observations, decides when a skip is learned, holds the thresholds and the on/off setting. Thread-safe singleton. |
| `MusicService.java` (section `SMART SKIP`) | Hooks into playback: learns from user seeks and Next, applies skips when a track becomes ready, and checks the outro on the 1-second progress tick. |
| `SettingsActivity.java`, `activity_settings.xml` | Switch and "Forget learned skips". |
| `TrackDetailsDialog.java` | Shows learned intro/outro. |

### The user/system boundary

Learning depends on telling user actions apart from the app's own:

- `MusicService.seekTo()` and `MusicService.next()` are the **user** entry points
  (UI, notification, MediaSession). They learn, then act.
- Internal code must **not** call them. Smart Skip's own jump uses
  `mediaPlayer.seekTo()` directly, and track completion and auto-outro use the
  private `advanceToNext()`.

If you add a new code path that seeks or advances on the app's behalf (a sleep
timer, crossfade, gapless queue, an ExoPlayer migration), call the player or
`advanceToNext()` directly. Routing it through the public methods would make
Smart Skip learn from things the user never did.

### Testing manually

1. Pick a song longer than 1 minute.
2. Within 15 s of the start, drag the seek bar forward to about 0:30. Repeat on a
   second play, landing within 5 s of the first point.
3. Play the song a third time. It should start at the earlier point and show the toast.
4. Seek to 0:00 straight away. You should see "this intro will play from now on",
   and the next play should start at 0:00.
5. For outros, press Next at about 80 % of a track on two plays, then let the
   third play run. It should move on at the later of the two points.

Track Details shows the learned positions at each step.
