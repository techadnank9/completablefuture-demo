# Concurrency with `CompletableFuture`

A live-demo project for a Java class presentation (Trine University).

It answers one question with real, measured numbers: **what does asynchronous
programming actually buy you?** It does that twice — once with a serious piece of
engineering (a concurrent file downloader), and once with a friendly example
everyone already understands (a coffee shop).

Zero runtime dependencies. JDK only. Runs with no internet connection.

---

## What it demonstrates

**Round 1** downloads a 64 MB file the obvious way: one HTTP GET, one stream, one
thread. **Round 2** downloads the *same* file as 8 parallel HTTP range requests
composed with `CompletableFuture`, writing every chunk straight into its own
offset of one pre-allocated file.

Then it puts the two side by side and proves they produced the same bytes:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━ RESULT ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                  Sequential          Concurrent (8 chunks)
Time              10.62 s             1.29 s
Avg speed         3.0 MB/s            24.8 MB/s
Threads used      1                   8
SHA-256           1fd8...4101         1fd8...4101         identical
Speedup                               8.2x
```

The speed is only half the point. The matching SHA-256 is the other half:
eight threads wrote into one file at eight different offsets, and not one byte
landed in the wrong place.

---

## Running it

Requires **Java 17 or newer** (tested on 17 and 21) and Maven.

```bash
mvn clean package
java -cp target/classes demo.App
```

or, without building a jar:

```bash
mvn exec:java -Dexec.args="download"
```

**From IntelliJ:** open the project and press the green arrow on `App.main`.
If the progress bars look garbled in the run console, add `--plain` to the run
configuration's program arguments.

### Commands

| Command | What it does |
|---|---|
| `java demo.App` | Interactive menu: 1) download 2) coffee 3) both q) quit |
| `java demo.App download [options]` | The download comparison |
| `java demo.App coffee [options]` | The coffee shop scenarios |
| `java demo.App both [options]` | Both, in presentation order |

### Download options

| Flag | Default | Meaning |
|---|---|---|
| `--local` | on | Start the built-in throttled server and download from it |
| `--url <url>` | none | Real mode: download this URL instead of the local file |
| `--chunks <n>` | 8 | Number of parallel chunks |
| `--size-mb <n>` | 64 | Local mode only: size of the generated test file |
| `--throttle-mbps <n>` | 3 | Local mode only: max MB/s **per connection** |
| `--compare-pools` | off | Extra round: 1/2/4/8/16/32 chunks, printed as a table |
| `--out <dir>` | `./downloads` | Where files are saved |

### Coffee options

| Flag | Default | Meaning |
|---|---|---|
| `--orders "Name:DRINK[:PASTRY],..."` | none | Orders on the command line |
| *(no flag)* | | Prompts for orders; Enter on an empty line uses 5 defaults |
| `--baristas <n>` | 4 | Size of the barista thread pool |

### Shared options

| Flag | Meaning |
|---|---|
| `--no-pause` | Never wait for Enter, and never prompt — the full rehearsal in one command |
| `--plain` | No ANSI colours or cursor movement |
| `--debug` | Print stack traces instead of friendly messages |
| `-h`, `--help` | Usage |

### Rehearse the whole thing in one command

```bash
java -cp target/classes demo.App both --no-pause --plain
```

---

## Local mode and why it is throttled

On localhost a 64 MB file copies in well under a second, so both rounds would
look instant and the demo would prove nothing.

`LocalFileServer` therefore limits each connection to 3 MB/s, with a 24 MB/s
ceiling shared across all of them. That is not a trick — it is what real servers
and CDNs do, and it is precisely *why* opening eight connections is faster. The
client code being demonstrated is exactly the code you would write against the
real thing: `Accept-Ranges: bytes`, `Range:` requests, `206 Partial Content`.

The generated test file uses a fixed random seed, so its SHA-256 is identical on
every machine and every run.

## Real mode

```bash
java demo.App download --url https://example.com/some-large-file.bin
```

Any direct URL that supports byte ranges works. The app probes it with `HEAD`
first and tells you what it found; if the server does not support ranges it says
so and runs the sequential round only, rather than failing mid-demo.

**Expect a smaller speedup than in local mode, and say so out loud — it is a
talking point, not a bug.** Concurrency only helps where the bottleneck is
per-connection. Two things observed while testing this project:

- A **GitHub release asset** serves parallel ranges correctly, but is not
  throttled per connection, so the measured speedup was only about **1.1x**. The
  SHA-256 still matched. The lesson: parallelism is not free performance, it is a
  tool for a specific bottleneck.
- A **public speed-test mirror** (`proof.ovh.net`) advertises `Accept-Ranges` and
  serves a single range fine, but answers **429** to eight simultaneous
  connections. The retry and timeout logic handled it and reported a clear error
  instead of crashing — which is itself worth showing if it happens live.

The fix is to host a server that *does* limit each connection — see below.

Verify any candidate URL before trusting it:

```bash
curl -I <url>                              # expect Content-Length and Accept-Ranges: bytes
curl -o /dev/null -D - -r 0-1023 <url>     # expect 206 and Content-Range
```

---

## Hosting a real target: `serve` mode

The same `LocalFileServer` that powers the offline demo can run as a long-lived
public server, so real mode downloads over the actual internet **and still shows
the speedup**:

```bash
java demo.App serve --port 18080 --size-mb 8 --throttle-mbps 0.4
```

```bash
java demo.App download --url https://concurrency-demo.onrender.com/sample_video.mp4
```

### Tuning it for the room

This is the part that decides whether the demo works. The per-connection limit
only matters if it is **tighter than the bandwidth available in the room**. Cap
each connection at 3 MB/s in a hall with 25 Mbps, and eight chunks just saturate
the hall's uplink — no speedup appears, and the fault looks like yours.

With N chunks the peak aggregate is `N × throttle`. The shipped defaults keep that
small on purpose:

| Setting | Default | Effect |
|---|---|---|
| `DEMO_SIZE_MB` | 8 | File size |
| `DEMO_THROTTLE_MBPS` | 0.4 | ~20 s sequential, ~2.5 s at 8 chunks, ~26 Mbps peak |

Measured against a local `serve` instance at those defaults:

```
Time              19.93 s             2.46 s
Avg speed         0.4 MB/s            3.3 MB/s
Threads used      1                   8
SHA-256           7522...61f6         7522...61f6   identical
Speedup                               8.1x
```

Both are environment variables, so they can be retuned from the Render dashboard
after testing on the real network — no redeploy needed.

### Deployed

**Live at https://concurrency-demo.onrender.com** (Docker, Render Starter).

Render's proxy passes byte ranges through correctly — confirmed, not assumed:

```
$ curl -o /dev/null -D - -r 0-1023 https://concurrency-demo.onrender.com/sample_video.mp4
HTTP/2 206
content-range: bytes 0-1023/8388608
```

Measured over the real internet against that service:

```
Time              20.12 s             3.85 s
Avg speed         0.4 MB/s            2.1 MB/s
Threads used      1                   8
SHA-256           7522...61f6         7522...61f6   identical
Speedup                               5.2x
```

5.2x rather than the 8.1x seen on localhost, because real network latency and the
client's own uplink now sit in the path. That gap is worth saying out loud: the
ceiling is set by whatever the tightest constraint is, and over the internet it is
no longer purely the server's per-connection limit.

To redeploy from scratch: **New → Blueprint → pick this repo**. Render reads
`render.yaml`.

Endpoints: `/sample_video.mp4` (the file), `/health` (liveness), `/` (a plain-text
description of what is being served and at what limit).

---

## Talking points

Each round maps to one idea and to the API that expresses it.

| Moment | What is on screen | Concept |
|---|---|---|
| Round 1 | Sequential download, one thread | Blocking I/O wastes time; the thread is *waiting*, not working |
| Round 2 | 8 chunks downloading at once | `supplyAsync`, `allOf`, `thenApply` |
| Result panel | Matching SHA-256 | Correctness under concurrency; non-overlapping ranges need no locks |
| `--compare-pools` | The 1/2/4/8/16/32 table | Custom executors, sizing a pool to the work |
| Coffee 1 and 2 | One barista vs four | The same idea in everyday terms, plus `anyOf` |
| Coffee 3 | Grind → brew → steam, and the pastry | `thenCompose` vs `thenCombine` |
| Coffee 4 | The machine breaks or hangs | `exceptionally`, `orTimeout`, `completeOnTimeout`, `handle` |

### The pool-size table is the resource-management slide

```
  Chunks    Time          Speed         vs 1 chunk
  ------------------------------------------------
  1         5.29 s        3.0 MB/s      1.0x
  2         2.62 s        6.1 MB/s      2.0x
  4         1.29 s        12.4 MB/s     4.1x
  8         621 ms        25.8 MB/s     8.5x
  16        628 ms        25.5 MB/s     8.4x
  32        623 ms        25.6 MB/s     8.5x
```

Perfectly linear to 8, then flat. Past that point the bottleneck is total
bandwidth, and the extra 24 threads buy nothing while still costing memory,
context switches and connections. **More threads is not a strategy.**

### `thenCompose` vs `thenCombine`

The single most common point of confusion, and Scenario 3 exists to settle it:

- **`thenCompose`** — step 2 *needs* step 1's result. You cannot brew before you
  grind. It flattens the nested future that `thenApply` would leave you holding.
- **`thenCombine`** — two *unrelated* futures that have been running side by side.
  The croissant never needed the coffee, so it warms while the coffee brews and
  the pair costs 2.5 s instead of 4.0 s.

### Never block the common pool

`CompletableFuture.supplyAsync(task)` with no executor runs on the shared
`ForkJoinPool.commonPool()` — sized to `availableProcessors() - 1`, shared with
parallel streams and every other library in the JVM, and designed for short
CPU-bound work. Every `supplyAsync` and `runAsync` in this project passes an
explicit executor from `Executors2`, and every pool is shut down in a `finally`.

This was not theoretical. An early version of this code leaked the `HttpClient`'s
executor: `main` returned, the result panel printed, and the JVM then **hung
forever** because those threads are not daemons. Creating a pool is only half of
owning it. See the comment in `DownloadDemo`'s `finally` block.

---

## Finding the code during the talk

Every key line is marked with a `// DEMO:` comment, so you can jump straight to it
in IntelliJ (Edit → Find → Find in Files → `// DEMO:`).

```
src/main/java/demo/
├── App.java                      Menu and argument parsing
├── common/
│   ├── Executors2.java           Named, sized pools; why not the common pool
│   ├── Log.java                  [+elapsed] [thread-name] message
│   ├── Console.java              Banners, colours, pauses, --plain
│   ├── Stopwatch.java            Elapsed time
│   └── Bytes.java                Human-readable sizes and speeds
├── download/
│   ├── DownloadDemo.java         Orchestrates the rounds and the result panel
│   ├── FileProbe.java            HEAD: size and Accept-Ranges
│   ├── ChunkPlan.java            Splits [0, size) into N exact ranges
│   ├── SequentialDownloader.java Round 1, the baseline
│   ├── ChunkedDownloader.java    Round 2  <- the centrepiece
│   ├── ProgressTracker.java      Lock-free per-chunk counters
│   ├── ProgressRenderer.java     Redraws the bars on a timer
│   ├── Integrity.java            SHA-256
│   ├── DownloadResult.java       One round's numbers
│   └── server/
│       ├── LocalFileServer.java      HTTP server with range support
│       └── ThrottledOutputStream.java Token-bucket bandwidth limit
└── coffee/
    ├── CoffeeDemo.java           The four scenarios
    ├── model/                    Drink, Pastry, Order, Served
    └── service/
        ├── CoffeeShop.java       All the orchestration  <- the centrepiece
        ├── Barista.java          grind / brew / steam, each async
        ├── PastryStation.java    Warms pastries in parallel
        ├── EspressoMachine.java  Failure injection: break or hang
        └── Tempo.java            Simulation speed (tests run at 0.25x)
```

---

## Tests

```bash
mvn test
```

32 tests, about 22 seconds.

| Test | Checks |
|---|---|
| `ChunkPlanTest` | Ranges cover every byte exactly once; no overlap; odd sizes; n > size; n = 1 |
| `LocalFileServerTest` | HEAD reports size and ranges; GET range returns 206 with the right bytes; invalid range returns 416; parallel ranges reassemble |
| `ChunkedDownloaderTest` | SHA-256 matches the source; chunked beats sequential under a per-connection throttle; a failing chunk is retried; a permanently failing chunk reports its real cause |
| `CoffeeShopTest` | Concurrent beats sequential; `anyOf` reports the fastest drink; the pastry overlaps the brew; a broken machine falls back for that order only; a hang trips the timeout; others are still served |

The coffee tests run the real code through `Tempo(0.25)`, so a latte takes 625 ms
instead of 2.5 s. Every timing *relationship* the tests assert on is preserved —
only the wall-clock cost of the suite changes.

---

## Slides

`CompletableFuture-Presentation.pptx` — 11 slides, with speaker notes on every one,
built around the numbers this code actually produced. The structure mirrors the
talk: the problem, the API, live demo 1, results, resource management, live demo 2,
composition, resilience, takeaways.

Re-measure on the presentation laptop and update the figures on slides 6, 7 and 8
if they differ noticeably from the ones shipped here.

---

## Rehearsal checklist

- [ ] `mvn clean package && mvn test` on the presentation laptop
- [ ] `java -cp target/classes demo.App both --no-pause --plain` end to end
- [ ] Decide local vs real mode **after** testing the classroom network
- [ ] Increase the IntelliJ console font size for the projector
- [ ] Check whether the console needs `--plain`
- [ ] Test screen sharing the IntelliJ window in Teams
- [ ] Keep `downloads/` open in Finder/Explorer to show the finished files
- [ ] Open the deck in PowerPoint once to confirm the fonts render as expected
