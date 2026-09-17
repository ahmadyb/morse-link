# MorseLink — Bugs, Fixes, Features and Open Issues

A complete record of the project from the first build to the current APK.

- **Package:** `com.morselink.app`
- **Current build:** versionCode **279**, versionName **1.1.179**
- **APK:** `app/release/morse-link.apk` (8,844,309 bytes, md5 `e46c4d63e62dee6208e92add07c142e1`)
- **Branch:** `arena/01a06c1e-morse-link`
- **Min SDK:** 21 (Android 5.0) → **Target/compile SDK:** 35 (Android 15)
- **Accent:** `#1FA36B`

Every build is produced by GitHub Actions; there is no local Android SDK in the
development sandbox. See [How a build is made](#how-a-build-is-made) at the end.

---

## 1. What the app is

Offline peer-to-peer file transfer. No cloud, no accounts, no ads, no analytics.
Three transports, tried in order:

| Transport | How it works | Notes |
|---|---|---|
| **Nearby Connections** | Google Play Services Wi-Fi Direct | Primary where GMS exists |
| **Legacy Wi-Fi P2P** | Manual TCP protocol — 64 KB chunks, CRC per chunk, resume from offset | Works without GMS; the workhorse |
| **WebShare** | NanoHTTPD server on port 33455 over the hotspot, with mDNS | Lets any browser on the hotspot browse and download |

Four bottom tabs: **Connect**, **Files**, **History**, **Settings**.
A monochrome brand mark in deep green, generated as adaptive launcher icons.

---

## 2. Bugs fixed

Grouped by area. Each entry gives the symptom, the actual cause, and what was
changed. Where you confirmed the fix yourself it says **Confirmed**; where it
shipped but you have not yet said it works it says **Shipped, unconfirmed**.

### 2.1 Build, infrastructure and tooling

**Cross-module resources and dependencies would not compile.**
`app` and each feature module referenced strings and drawables through their own
generated `R`, which only contains that module's resources plus those of modules
it depends on. Sibling features are invisible to each other. `fragment_send.xml`
referenced `@string/transfer_panel_collapse` from `feature-transfer-ui` and failed
the release build. Fixed by using `core-ui` strings that every module can see.

**`tools/verify.py` was added and then repeatedly extended** because the local
gates could not see what the compiler sees. It now checks: resource string
references resolve in the module or its (transitive) dependencies; unimported
coroutine extensions; more than one companion object per class; capitalised names
never imported; and Java 8 collection default methods that do not exist below
API 24. It is **a linter, not a compiler** — it does not type-check, and several
red builds got past it.

**Injected types need their module on the classpath.**
`FileManagerFragment` injected `ConnectionHolder`, which lives in
`core-network`, but only `core-transfer` had been added to
`feature-filemanager/build.gradle.kts`. KSP failed with
`error.NonExistentClass`. Adding the dependency fixed it. **A lesson that cost
three builds: adding a type to a module means adding that type's module.**

**Missing imports caused four separate red builds.**
`kotlinx.coroutines.launch`, `isActive`, `cancelAndJoin`, `TransferDirection`,
`emitAll`. Each is invisible to the linter and fatal to the compiler.

**Nested data classes are not allowed inside an inner class.**
A `private data class ControlLine` declared in the body of the `inner class
LegacySession` failed with `Class is not allowed here`. Hoisted to file scope.

**`suspend` propagation.** Making the control-channel mutex correct required
`streamFile`, `readChunks` and `receiveChunks` to become `suspend`; each caller
had to follow.

**CI workflow.** Builds are signed with an in-repo keystore. A failing build
commits its `build.log` to the branch so the error is readable (log fetching is
blocked in the sandbox); a succeeding build removes it. Without the removal, one
old failure left a `build.log` on the branch permanently and it read as though
the build were still broken.

**The version changes on every single build.** The workflow passes
`100 + run_number` as `versionCode` and `1.1.<run_number>` as `versionName`.
*Confirmed.*

### 2.2 Transfers — the core of the work

**Nothing was ever received. Phone-to-phone transfer had never worked.**
`TransportSession.incomingFiles()` is declared on the interface and implemented
by both transports, but **nothing in the app ever called it** — and that flow is
what starts the receive loop. The receiving phone completed the TCP handshake,
showed "Connected to *name*", and then never read a byte. The sender wrote its
metadata and blocked for thirty seconds until its socket timed out. Every
"Wi-Fi Direct transfer failed / Read timed out" report was this one cause.
Fixed by `IncomingTransferCoordinator`, which holds the collection at *process*
scope so receiving survives the user leaving the screen. **Confirmed.**

**Multi-file sends died after the first file.**
`sendFile()` performs a full metadata handshake per file, but `receiveLoop()`
read a single metadata line and then called `closeSockets()`. Every file after
the first failed with "Socket closed". The loop now keeps reading metadata lines
and delegates each file to `receiveOne()`. **Confirmed.**

**Files were truncated — the tail of every transfer was lost.**
`sendChunk` wrote into a 64 KB `BufferedOutputStream` that was never flushed.
The sender counted a chunk as sent the moment it was handed to the buffer, so it
reported 100% while up to 64 KB sat in memory; the receiver waited for bytes
that never arrived. A large file stalled at a high percentage; a small one never
arrived at all. Each chunk is now flushed as written. **Confirmed.**

**Sending threw `NullPointerException` on every file.**
`sendFile` took `dataSocket!!.channel` and wrote chunks through that
`SocketChannel`. A `Socket` only has a channel if one created it — sockets from
`ServerSocket.accept()` and `Socket(host, port)` return `null`. Chunks now go
through a buffered `OutputStream`.

**A socket timeout in the receive loop killed the app.**
The loop runs in the transport's own coroutine, so nothing upstream could catch
it and it went to the thread's uncaught handler — a `FATAL EXCEPTION` on both
handsets. `receiveChunks` now reports failure by returning `false`, and the loop
wraps each file and the loop as a whole. Cancellation is still rethrown so Cancel
keeps working.

**Idleness tore the session down.**
The loop read with a thirty-second timeout and treated the resulting exception
exactly like the peer hanging up. A receiver that waited more than thirty
seconds — which anyone does while going off to pick files — destroyed the whole
session. Idle now continues the loop; only a genuine close ends it.

**The second file of a turned-around batch arrived with zero bytes.**
The data socket was cached and reused for the whole session, across files and
across the change of direction, so after a turnaround the receiver held a socket
that was open but silent. Each file now gets its own connection.

**A CRC mismatch asked for the chunk again — which corrupted the file.**
The sender appended the repeat to the end of the same stream, so it landed after
chunks belonging later in the file and was written at the wrong offset: the file
ended up exactly the right size and silently wrong. TCP already guarantees the
bytes, so a mismatch means the framing slipped. It now fails the file with a
message saying so.

**One bad file used to end the whole batch.** A throw inside the per-file handler
propagated out and took every file queued behind it. Files are framed
independently, so the loop now logs, fails that one file, and carries on.

**The logged byte count never advanced.** `receiveChunks` kept a local counter
set to `startOffset` and never updated it, so every failure reported
"0/3351426 B" no matter how far the file had got. The diagnostic that would have
identified the other faults was reporting nothing. It now reads the part file.

**The receiver could never send back.** The control socket carries both
directions and only one side may talk at a time, so after sending, the sender has
to start listening; nothing did. `OutgoingTransferCoordinator` now reports when a
batch finishes and the screen hands the channel over. *Partially confirmed — see
the turnaround history below.*

**The sender never recorded a successful send.** Only the failure branch called
into the engine, so a transfer that worked left its row unfinished and wrote no
`SENT` entry — the receiving handset had a list and the sending one showed
nothing for the very same files. Both transports now call `complete()` on
success.

**API 23 crashed on startup — five crashes in seventeen minutes.**
`NoClassDefFoundError: TransferEngine$$ExternalSyntheticLambda1` at
`clearFinished`. It used `entries.removeIf { }`; `Collection.removeIf` is a Java 8
default method, **API 24**. It compiles cleanly against compileSdk 35 and then
fails on an older device. This was also the "Cancel that does nothing": the
screen could not survive its own constructor. Replaced with an iterator loop.
The same trap was in `FileBrowser.storageRoots` (`Map.putIfAbsent`, also API 24)
on the SD-card path. `verify.py` now rejects these calls outright.

**Received photos were dated 1 January 1970.**
Below API 29 the code called `MediaStore.Images.Media.insertImage`, whose Bitmap
overload writes no `DATE_TAKEN` at all, and galleries sort by that column. Above
it, only `DATE_ADDED` was written. The legacy branch now inserts directly with
every date filled in, which also stops it re-encoding the bitmap (insertImage
decoded and re-saved, discarding quality and EXIF). `DATE_TAKEN` is in
milliseconds, `DATE_ADDED`/`DATE_MODIFIED` in seconds — mixing those up is
another route to 1970, so the units are commented at the call site.
**Confirmed.**

**The pushback buffer overflowed on WebShare upload.** The stream read in 64 KB
but the `PushbackInputStream` was built with 8192, so any part whose trailing
bytes did not fit was discarded — uploads read 100% in the browser and then never
appeared.

**Empty uploads reported success.** The response now carries the real byte count
and the saved path.

### 2.3 The turnaround — eight attempts, and what each one actually fixed

This is the single hardest defect in the project and it deserves its own
section, because almost every attempt fixed something real and the symptom did
not go away.

**Attempt 1 — idleness.** The loop treated a thirty-second socket timeout as the
peer hanging up and closed the session. Real fault, fixed. Still failed.

**Attempt 2 — the `finally` block.** `finally { closeSockets() }` ran on *every*
exit, including cancellation to hand the channel to a send. The act of switching
direction destroyed the session. A `handedOver` flag now limits teardown to a
genuine finish. Real fault, fixed. Still failed.

**Attempt 3 — the handover's location.** Your logs showed device B's receive
loop still running thirty seconds after its own send had begun; it only ended
when the send timed out. The handover was being performed by whichever screen
happened to start the send, and that is not every path. It moved inside `send()`
itself, so no path can skip it. Real fault, fixed. Still failed.

**Attempt 4 — two readers on one socket.** The log finally produced a line that
named the fault outright:

```
rx: skipping control line: {"yp""euevle:0tt:
```

That is not a corrupt message. It is **two readers tearing one line between
them** — `readLine()` is not thread-safe. A `sending` flag now claims the channel
for the duration of a send and the loop's while-condition honours it. Real fault,
fixed. Still failed.

**Attempt 5 — the design (current).** The decisive evidence was a clean pair of
logs with no interleave at all:

```
07:09:11.362  tx: metadata sent, waiting for resume      (sender)
07:09:11.493  rx: resume sent                            (receiver)
07:09:41.399  tx: FAILED SocketException: Socket closed  (sender, 30 s later)
```

The receiver replied in 131 ms. The sender recorded nothing at all — and there
was no `skipping control line`, so nothing stole it either. The reply simply
evaporated. A flag can stop a loop from *starting* a read; it cannot stop a read
already in flight, and it cannot stop a second reader being inside the socket
when a send begins.

So the control channel now has **one owner**. Every read and every write, on
either side, goes through a single `channelMutex`:

- The receive loop holds it only for the read itself, so a send waits at most one
  poll interval.
- The send holds it **across write-then-read**, because releasing between the two
  is precisely how the loop got the socket back and consumed the resume first.
- Non-metadata lines, the `DONE` marker, `REJECT`, `RETRANSMIT` and the resume
  write are all inside the lock.

**Status: shipped, unconfirmed.** You reported "failed" again.

**Attempt 6 — the hand-over the log had been pointing at.** The new logs were
decisive because of the timing:

```
20:56:36.976  rx: receiver loop ended
20:56:36.977  tx: metadata sent, waiting for resume
20:56:36.979  tx: FAILED SocketException: Socket closed
```

One millisecond between the loop ending and the send dying. The loop's `finally`
closes the sockets unless it has been told the exit is a hand-over — and it was
only ever told that on **cancellation**. Attempt 4's `sending` flag gave the loop
a *second* way out, and every exit through that door looked like a genuine
finish, so the session was destroyed in the instant before the send that needed
it. This is the same class of fault as attempt 2, reintroduced by a new exit
path.

The loop now records *why* it left, at the moment it leaves, rather than
inferring it afterwards from a flag the send may already have cleared.

**A second fault, from the same logs.** A send that began while a file was still
being received failed outright:

```
20:56:45.868  tx: FAILED BindException: bind failed: EADDRINUSE
```

`reuseAddress` was already set, so the port was genuinely held — the in-flight
receive owned it. The send now lets a receive finish first, closing its data
socket so a receiver parked in a read unwinds immediately instead of blocking for
its full thirty-second timeout.

**Attempt 7 — sessions that died while nothing was happening.** Reading the two
logs end to end turned up a failure mode none of the six attempts addressed:
sessions ending on their own, with no transfer in flight.

```
20:54:26.737  rx: Morselink done
20:54:41.258  rx: control channel closed (SocketException)   ← 14.5 s later
```
```
19:03:47.515  rx: done
19:05:21.969  rx: control channel closed (SocketException)   ← 94.5 s later
```

Between transfers the control channel carries **nothing at all**, and the link
drops it — after fifteen seconds in one case, ninety-five in another. After that
every send fails with *"The connection to <peer> was closed"* no matter how
carefully the hand-over is done. That is very likely the "transfer failed again"
report, and it is invisible to any fix that only looks at the moment of the
hand-over.

The receive loop now writes a keepalive whenever the channel has been silent for
**five seconds** — well under the shortest idle death observed. Two things make it
safe: senders step over keepalives when reading a resume (otherwise a heartbeat
arriving mid-handshake would read as "no resume", start the file from zero, and
leave the real reply in the buffer to be picked up as the answer to the *next*
file's handshake), and receivers ignore them rather than logging them as junk
every five seconds. A peer on an older build simply sees a line it does not
recognise and skips it, so old and new builds interoperate.

**Attempt 8 — the session that outlived its connection.** This one turned out to
be the cause of the missing QR code as well, and it is in §2.4. Its effect on the
turnaround is direct: with a dead session still recorded, every later send was
aimed at a socket that had been gone for minutes.

**A third exit that lied about itself.** Attempts 2, 4 and 6 each closed one way
out of the receive loop that pretended to be a finish. There was one more: the
loop's own `isActive` check, which breaks without setting any flag. A cancel
observed there left `handedOver` false, so the loop closed the sockets exactly as
if the peer had hung up. Counting on `CancellationException` alone was never
enough, because the loop can also simply *notice* it has been cancelled. The
`finally` block now asks the coroutine context directly, and any cancellation —
however it was observed — counts as a hand-over. Cancellation is permanent, so
one question covers every path.

**Status: shipped, unconfirmed.**

### 2.4 UI — Send, Files, transfer screen

**Settings switches rendered at zero width and could not be tapped.**
`item_settings_switch.xml` used `MaterialSwitch`, the Material 3 widget, which
resolves its default style through `R.attr.materialSwitchStyle` — only M3 themes
define it. The app theme descends from MDC-2, so the thumb and track drawables
resolved to `null` and the view measured 0 px. Swapped to `SwitchMaterial` with
an explicit `Widget.Morselink.Switch`.

**The app log could never be switched on.** Tapping the row opened the viewer
instead of toggling, so "Logging is switched off" was a dead end. The row now
toggles; a separate row opens the viewer.

**Cancel on the transfer screen bounced back to where it started.**
`ReceiveFragment` observed a `LiveData` and navigated on `true`. `LiveData`
replays its last value to every new observer, so cancelling popped back to
Receive, which re-created its view, re-observed, received the stale `true`, and
navigated straight back in — by then with the session closed. The fragment now
consumes the flag before navigating.

**The green "Connected" card stayed lit after the session died.** The session was
a plain field on `ConnectionHolder`, so when the other phone walked away nothing
changed on screen and the app kept offering to send into a connection that was
not there. It is now published as a flow the screens watch. The flow starts
`false`, so reacting to every `false` would print "Connection closed" the instant
the screen opened — only a session actually seen and then lost counts.
**Confirmed.**

**Stop on the notification only stopped the service.** It left the sockets open
and the files still moving, with the notification — the only place showing
progress — gone. It now closes the session too. **Confirmed.**

**History never showed a thumbnail.** The row layout was a 32 dp `ImageView`
with a fixed `ic_file` drawable and a `textSecondary` tint that would have
coloured any thumbnail, and the adapter never loaded anything. Now 44 dp, tint
removed, Glide for photos and a frame pulled with `MediaMetadataRetriever` for
videos (Glide will not decode a thumbnail from a path on older Android). Every
load cancels the previous one and stamps the view with a token, so a recycled
holder cannot be painted with the wrong row's image.

**Switching between Sent and Received re-emitted nothing.** The rows are a flow
over the database but the direction was held in a `MutableLiveData` outside it,
so the filter only re-ran on the next database write and both tabs showed
whichever list had been built last. The direction is now part of the flow via
`combine`.

**Music and Files selected but showed no tick.** `ListHolder.bind` called
`resetSelection` and never showed it again. Any row that is not a header or a
category shortcut now shows the tick, with a translucent accent wash so the
selection is legible without hiding the text.

**Sending left the files still ticked**, so returning to the screen showed a
selection already on its way. The queue owns them now. Clear also drops anything
already queued, which was invisible on that screen but still in line to be sent.

**The address bar collapsed to nothing.** With no segments the trail measured
only its spacer, one pixel tall, so the bar disappeared — no way to type a path
and no way back up. The trail has a minimum height now, and the spacer fills the
full height so the tap target is the whole bar.

**Two faults I introduced myself and then fixed** (both in the same change that
made Send connect-only):

- **Minimise did nothing but flicker.** It navigated to `morselink://send`, and I
  had re-pointed `send` at the transfer screen — so it navigated to itself. It
  now goes to the Files tab.
- **The QR code stopped appearing.** The screen decided whether to pair by asking
  "are there files queued?", and Send now arrives with nothing queued, so it
  concluded it had been opened to receive and left the card blank. It needed a
  way to be told why it was opened.

  The first attempt used a query parameter, `morselink://send?asSender=true`, and
  **did not work** — deep-link query matching was not reliably delivering the
  flag, so the screen still did not know it was here to send. Send now has its
  own destination in the nav graph carrying `asSender = true` as a fixed default,
  which needs no parsing: which URI opened the screen decides what it is for.
  `morselink://transfer` is unchanged.

- **Why it *still* did not appear — a session that never died.** Neither of those
  was the whole story. `ConnectionHolder.session` was only ever cleared by
  `close()`, and `close()` only runs when *this app* ends the session. When the
  link dropped it, or the other phone simply walked away, nothing cleared those
  fields: `session` stayed set and `alive` stayed **true**, so the app went on
  claiming to be connected with no connection behind it.

  That alone explains the report "*even if i select files the qr code is not
  showing*", because there are two independent refusals:

  - `openAsSender()` returns early while `hasSession()` is true — it assumes the
    connected card is more useful than a code nobody needs to scan.
  - The arrival path only falls back to pairing when `session == null`. With a
    stale session it took the "already connected" branch instead and pushed the
    selection straight into a dead socket.

  So once a session had existed **even once**, no QR could ever appear again, and
  every later send was aimed at a socket that had been gone for minutes. The
  first attempts looked like navigation problems because that is what they
  presented as.

  The receive loop now releases the session when the channel has genuinely gone.
  It is the only place that can tell, because it is the only place that knows
  *why it stopped*: leaving through a hand-over — cancelled for a send, or
  because a send already took the channel — must leave the session standing.
  `ConnectionHolder.detach()` drops the session, the peer and the role, and
  publishes `alive = false`, so the UI stops offering to send into it.

  That the two symptoms shared one cause is worth stating plainly: this was
  reported as a QR bug, and it was a session bookkeeping bug.

**The transfer screen footer could settle part-way down the screen**, with the
Sending and Receiving lists hidden underneath it — on one handset only, and
**rendering correctly for a moment during a transition**. That last detail is the
tell: a constraint that resolves *late* rather than one that is simply wrong.
Rather than keep hunting for it, the screen is now a vertical stack — header,
card, list taking everything left over via `layout_weight="1"`, footer last. A
stack has nothing to resolve.

### 2.5 WebShare

**Videos had no thumbnails.** The grid used the download URL as an image source,
so the browser was pointed at a whole video file and asked to draw it. There is
now a preview endpoint that decodes a real frame, scales photos down instead of
serving multi-megapixel originals, and draws APK icons. **Confirmed.**

**Closing the video lightbox only hid it.** The `<video>` stayed in the document
playing, so sound carried on with nothing on screen and nothing to press to stop
it. Closing now removes the element, and stepping to the next clip stops the
previous one. **Confirmed.**

**The music player could not be dismissed** and its button showed a play triangle
whether or not anything was playing. It has a close button that stops playback
and releases the audio, and the icon follows the audio. Starting another track no
longer leaves the previous one's progress on the bar. **Confirmed.**

**Zip downloads took a long time to start** because the archive was built in full
before anything was sent back. It streams through a pipe now. **Confirmed.**

**Documents could not be selected at all** — grid views had a tick in the corner
and tables had no selection UI whatsoever, which is why bulk download said
nothing was selected. Tables now have checkboxes, select-all, and a count bar.
**Confirmed.**

**Folder grouping existed and was never seen.** It lived only in the side panel,
which on a phone is a drawer parked off-screen behind the hamburger. Folders are
sections on the page now, each with a button that downloads the folder as a zip.
**Confirmed.**

**Then: grouping shipped a second time and still did not appear — because the
browser was showing a cached page.** The HTML is compiled into the APK, so a
browser holding an old copy keeps rendering the previous build's interface. It is
served `no-store` now. **A feature that "does not show" may be a cached page,
not a missing feature.**

**All photos collapsed into a single folder.** `albumKey()` fell back to the
MediaStore URI, and `content://media/external/images/media` is **identical for
every photo on the device** — one key matched the whole library. The server now
sends the real path and `bucketName` and the key comes from the path, so Camera,
WhatsApp Images and Telegram are separate folders. **Shipped, unconfirmed.**

**An empty directory replaced the whole page with one line of text**, taking the
breadcrumb with it — no address bar, no way back up out of a folder you had
opened. `emptyView()` and `wireCrumbs()` were written to fix this and **were never
called**; `render()` still had the early return that threw the page away. The fix
was dead code sitting next to the bug. It is wired now. **Shipped, unconfirmed.**

**WebShare logging.** The server had exactly one log line, and it went only to
logcat, never to the in-app file. It now reports start and stop, each request,
and every upload with the byte count actually written — including the case where
a file arrives empty.

### 2.6 Diagnostics

**The exported log carried 1 Morselink line against 1,120 lines of NanoHTTPD
stack frames.** The capture filter no longer asks for `*:E`; it takes our own tag
plus `AndroidRuntime`.

**Log lines went only to logcat**, a rotating buffer shared with every process —
which is how an export ended up with a single line in it. They go to the in-app
file as well.

**Clear did not clear the log.** It deleted the app's own file but the viewer
also prints live logcat, which lives in a system buffer this app cannot clear
(that needs `READ_LOGS`, withdrawn from third-party apps at API 16). Clear now
records the moment and drops logcat lines older than it.

**Export did not exist.** A Settings row writes the whole log to
`morselink-log-yyyyMMdd-HHmmss.txt` and offers it to another app through the
`FileProvider`; the crash log dialog gained an Export button too.

**Handshake steps are now logged** — metadata sent, resume received, data socket
open — so if a transfer stalls, the log says which side is waiting and on what
rather than just reporting a timeout thirty seconds later. Attempt 4 of the
turnaround was only diagnosable because of this.

---

## 3. Features and UI added

### Screens

- **Connect** (dashboard) — animated radar showing discovered peers, with Send /
  Receive / WebShare entry points.
- **Send** — see below; now the connect-and-pair flow.
- **Files** — the file manager, and now **the picker**. See below.
- **History** — Room-backed, Sent / Received tabs, thumbnails, open and share.
- **Settings** — device name, sound effects, app log with export and clear,
  storage info, theme.

### Send → connect only

Send is a request to connect, not to browse. `morselink://send` opens the
transfer screen, which shows the pairing QR; once connected, a **Choose files**
button hands over to the file manager. Shares from other apps queue straight into
the transfer screen rather than opening a picker.

Both orders work: **connect first, then pick**, and **pick in the file manager,
then connect**. The file manager is the picker either way, rather than there being
a second, separate list on the Send tab.

### Files tab — the picker, in the older Send-tab style

Built to match a reference screenshot you supplied:

- **Tabs across the top** — Photos / Videos / Music / Apps / Files
- **Search local files**
- **Sort** — Date, Size, Name
- **Today / Yesterday / Earlier** headings
- **Media as a 3-wide grid**, with headings full width so it reads as sections,
  gutters between tiles, and selection shown as a wash over the thumbnail plus a
  tick rather than a small mark in the corner
- **Footer that says what you are about to send** — "Send 6 · 661.5KB"
- Directory navigation with an interactive address bar: tap a segment to jump up,
  tap a caret for sibling folders, tap the empty tail to type a path
- Long-press toolbar: send, share, delete, rename, move, copy, compress,
  properties
- **APKs show the icon of the app inside them**, decoded off the main thread and
  keyed to the row so a recycled view is left alone

The tab strip follows the list rather than only leading it — opening Photos from a
row, or pressing Back out of a folder, moves the list without touching the strip.

### Transfer screen

- **Collapsible pairing card with two faces**: "Scan to connect" (QR) while
  waiting, and "Connected to *peer*" on both ends once the session is up — so the
  receiving phone gets a connected panel too. Tapping the title row toggles it;
  switching face re-expands it, because someone who collapsed the QR and then had
  a receiver connect has new information, not something to hide for good.
- **Minimise** — keep the session alive, go and pick more files, send them over
  the connection that is already up. Previously the only way off the screen was
  Cancel, and Cancel closes the session, so a second batch meant pairing again
  from scratch. This required moving sends to `OutgoingTransferCoordinator`, a
  process-scoped mirror of the incoming one: the work belongs to the session, not
  to whichever screen is showing it.
- Status line and panel hint both computed from live state. The hint used to be
  baked in at connect time, so a phone that sent first and then received kept
  saying "Sending your files now" over a list of files it was busy receiving.
- Cancel falls back to popping the back stack when `navigateUp()` has nowhere to
  go — the state a crash-restart leaves behind.

### Sound effects

Stored by a setting and read by nobody. Now wired to actual events — connection,
completion, failure — played from the service so they survive Minimise, and
skipped when the phone is silent or vibrating.

### WebShare

Bulk download, folder download as a zip, sort by date / name / size, grouping by
folder, video and photo previews, a music player that can be dismissed, uploads
with real byte counts, and an address bar that survives an empty directory.

**You said WebShare is now fine and you are satisfied with it.** It is the one
area of the app that is closed out.

---

## 4. Open issues

### 4.1 The turnaround — eight attempts, still unconfirmed

Send → receive → send back. Every change made so far is described in §2.3. **Not
yet confirmed by you**, and you have reported failure six times.

Two distinct causes are now known, which is why fixes to one kept appearing to do
nothing:

1. **The hand-over** (attempts 1–6, 8) — the receive loop destroying the session
   in the instant before the send that needed it.
2. **Idle sessions dying on their own** (attempt 7) — after 15 s to 95 s of
   silence the link drops the connection, and the next send fails with
   *"The connection to <peer> was closed"* regardless of the hand-over.

The second was invisible to every earlier attempt, all of which examined the
moment of the hand-over only.

### 4.2 The transfer screen layout on one handset — addressed, unconfirmed

Measured from your screenshots:

| | Screen | Buttons at | Sending/Receiving headers |
|---|---|---|---|
| **a6** | 720×1280 | y = **86%** (correct) | present |
| **a1** | 576×1280 | y = **66%** | **missing** |

In a1 the buttons sit about two-thirds down with roughly a third of the screen
empty below them, and the Sending / Receiving headers are not there at all.

The layout file already pinned the footer to the bottom, and I could not find
anything in it that would put the footer at 66%. **The detail that resolved it
came from you: it renders correctly for a split second when Minimise is tapped.**
That is the signature of a constraint that resolves late, not one that is wrong —
so the fix is not to find the offending constraint but to remove the dependency on
constraint resolution. The screen is now a vertical stack: header, card, list
taking what is left, footer last. A stack has nothing to settle.

**Shipped, unconfirmed.**

### 4.3 A missing reference image

You referred to `logs6/photo_1_2026-09-11_19-00-37.jpg`. There is no `logs6/`
folder in the repository and no file with that timestamp anywhere. The nearest
candidate, `logs5/photo_1_2026-09-11_10-30-14.jpg`, is a "Morselink keeps
stopping" crash dialog rather than a UI screen. The Files tab was built from
`l4.jpg` alone. If the second image showed something `l4` does not, please
re-upload it.

### 4.4 Carried over, believed resolved, not re-tested

- Slow WebShare start (zip streams now) — *likely fixed by the streaming change.*
- Selection persisting across sends, and Clear breaking the next batch — *both
  addressed, not separately re-tested since.*

---

## 5. How a build is made

There is no Android SDK in the development sandbox, and the Google and Maven
repositories are unreachable from it. **GitHub Actions is the only build path.**

The loop:

1. Local gates — `python3 tools/verify.py` (linter) and `aapt2 compile --dir`
   over every module's resources. **Neither compiles Kotlin; only CI does.**
2. Commit and push to `arena/01a06c1e-morse-link`.
3. Wait for the **push** run. Each push triggers two runs (pull_request and
   push); only the push run commits a refreshed APK.
4. If `build.log` exists on the fetched branch tip, the build failed and the
   errors are in it. If it is absent, the build is green.
5. `git reset --hard FETCH_HEAD`, record the size, md5, versionCode and
   versionName, and present the APK.

Because CI commits the APK back to the branch, **a push is frequently rejected
as non-fast-forward.** The recovery is always the same: fetch, `git reset --mixed`
onto the tip so the working tree is untouched, re-apply the change, drop
`build.log`, and re-commit. Never `git pull` or rebase.

### Working notes

- The development sandbox has been re-cloned several times mid-session, which
  resets git's history while leaving the working tree intact. Each time it
  happened the history was recovered by fetching and `reset --mixed`, and the diff
  was verified to contain only the intended edits before committing. Nothing was
  lost, but it is why a handful of commits appear twice — once as an original and
  once as a replay.
- `/tmp` is wiped on every restart, so the local OCR tooling and `aapt2` are
  rebuilt from npm/PyPI as needed. Neither is required to build the app.

---

## 6. Summary

| Area | Status |
|---|---|
| Transfers, one direction | **Working, confirmed** |
| Multi-file batches | **Working, confirmed** |
| Receiver initiating a send | **Working, confirmed** |
| Minimise and keep browsing | **Working, confirmed** |
| Minimise lands on the Files tab | **Working, confirmed** |
| History, thumbnails, Sent entries | **Working, confirmed** |
| Notification Stop | **Working, confirmed** |
| Sound effects | **Working, confirmed** |
| Version changes every build | **Working, confirmed** |
| WebShare (all features) | **Working — you said you are satisfied** |
| Files tab as the picker | **Working — you said it works fine** |
| APKs showing app icons | **Shipped, unconfirmed** |
| WebShare folder grouping by real path | **Shipped, unconfirmed** |
| WebShare address bar on empty folders | **Shipped, unconfirmed** |
| Send tab as connect-only | **Shipped, unconfirmed** |
| QR code on Send | **Shipped, unconfirmed** (three attempts: query parameter failed; dedicated destination shipped; the real cause, a session never released, now fixed) |
| Gallery grid gutters and selection | **Shipped, unconfirmed** |
| Transfer screen footer position | **Shipped, unconfirmed** |
| Session released when the link drops | **Shipped, unconfirmed** |
| Control-channel keepalive | **Shipped, unconfirmed** |
| **Bidirectional turnaround** | **Shipped, eight attempts, unconfirmed** |
