# Speaker script

Word for word, per slide. Read it as written if you want to; it is meant to sound
like a person talking, not like a paper being recited.

**Total: about 13 minutes**, with roughly 4 minutes of that being the live demo.
The slides carry every number, so if the wifi fails you keep going and nobody
notices.

Two things before you start:
- These lines are also in the deck's **speaker notes** (PowerPoint: *View → Notes
  Page*, or drag up the pane under the slide). In Presenter View they appear on
  your screen only.
- Have `concurrency-demo.onrender.com` open in a browser tab before you begin.

---

## Slide 1 — Title · 20 seconds

> Good morning. I want to talk about `CompletableFuture`, which is Java's tool for
> asynchronous programming.
>
> The subtitle is the whole argument, so I will say it once now and prove it twice
> later: this does not make your program compute anything faster. What it does is
> let your program do its **waiting** in parallel. That turns out to matter enormously.

*Move on quickly. The title slide is not where the talk happens.*

---

## Slide 2 — The problem · 90 seconds

> Here is a completely ordinary piece of work. We are downloading an eight megabyte
> file over one connection, from a server that limits each connection to zero point
> four megabytes per second. It takes about twenty seconds.
>
> Now look at what the machine is doing during those twenty seconds.
>
> Zero percent CPU. The thread is parked inside a system call, waiting for bytes to
> arrive from the network. It is holding about a megabyte of stack the entire time,
> and it is doing nothing whatsoever with it.
>
> This is the part people get wrong. When something takes twenty seconds, the
> instinct is to reach for a faster machine. But there is no computation happening
> here to speed up. The processor is idle. Buying more of it changes nothing.
>
> **This was never a CPU problem, so it cannot have a CPU answer.**

*If you pause anywhere in the talk, pause after that last line.*

---

## Slide 3 — What it actually is · 2 minutes

**Go slowly here. Everything after this slide is mechanism.**

> So what is a `CompletableFuture`?
>
> It is a value that has not arrived yet. And, more usefully, it is a written plan
> for what should happen the moment it does.
>
> Ordinary code asks for a value and stops until it is handed one. That stopping is
> exactly the waste we just measured on the previous slide.
>
> A `CompletableFuture` hands you the box straight away. The box is empty. The work
> to fill it is happening somewhere else, on another thread.
>
> And then you describe the rest of the job on that empty box. Transform the result
> when it arrives. Combine it with another one. Recover if it fails. Give up if it
> takes too long.
>
> None of that has run yet. You are writing instructions for a value you do not have.
> Only at the very end do you actually ask for the result — and by then, usually, it
> is already sitting there.
>
> The shift is from writing *"wait here, then do this"* to writing *"when this lands,
> do this"*. The thread stays free the whole way through, which means it can be handed
> somebody else's work instead of sitting idle.

---

## Slide 4 — Why the API looks like this · 75 seconds

> Java has had a `Future` since 2004. It was not enough, and it is worth knowing why,
> because it explains the shape of everything on the right.
>
> On the left is the old `Future`. You submit work, you get a handle back, and then
> there is exactly one thing you can do with that handle: call `get`, which blocks.
> That is the whole API. If you wanted to chain a second step onto the first, or
> combine two results, or recover from a failure, you wrote that yourself. By hand.
> In every project. Usually badly.
>
> Ten years of that is why `CompletableFuture` arrived in Java 8, and why it has so
> many methods. Every one of them exists because somebody kept having to write it.
>
> On the right, the whole pipeline is described up front, and nothing has blocked yet.

---

## Slide 5 — Fan out, fan in · 75 seconds

> Here is the shape that almost every solution takes.
>
> We start with one file. We cut it into byte ranges — eight of them here — and we
> request all eight at the same moment. That is the fan out, and it is one
> `supplyAsync` per range.
>
> Then we fan back in. `allOf` completes when every range has completed, and
> `thenApply` turns that into the finished result.
>
> The important detail is the one that makes it safe. The ranges never overlap, and
> the output file is allocated up front, so each range writes straight into its own
> offset. Nothing is shared, so nothing needs a lock — there is not a single
> `synchronized` anywhere in this code.
>
> And that is precisely why, on the next slide, the two checksums come out identical.

---

## Slide 6 — Measured · 60 seconds *(then the live demo)*

> These are real measurements, not estimates.
>
> Same file, nine megabytes. Both sides started at the same instant, against the same
> server, over the same connection — so nobody can argue that the second run simply
> caught a quieter moment on the network.
>
> One connection: twenty-one seconds. Eight connections: two point six seconds. Eight
> times faster.
>
> And the line that actually matters is underneath. The SHA-256 checksums are
> identical. Eight threads wrote into one file at eight different offsets, and not a
> single byte landed in the wrong place.
>
> Speed alone would not be worth much. Speed with a matching checksum is the result.

**→ Switch to the browser now.** Pick a file from the library on the left, press
*Download both ways*, and talk over it while it runs:

> The left side is one connection. The right is eight. Watch the clocks.
>
> Both sides end up with their own copy of the file, and you can open either one. Same
> picture. Same checksum. One of them just arrived about eighteen seconds earlier.

*If the network misbehaves, stop and go back to the slide — the numbers are on it.*

---

## Slide 7 — Sizing the pool · 75 seconds

> A fair question at this point is: if eight connections are good, why not eight
> hundred?
>
> This is the same download at one, two, four, eight, sixteen and thirty-two
> connections. Up to eight it is almost perfectly linear — each new connection buys
> real bandwidth, because the server limits each one separately.
>
> After eight it is flat. Completely flat. The bottleneck stopped being concurrency
> and became total bandwidth, and connections nine through thirty-two bought us
> precisely nothing.
>
> They were not free, either. Every one of them costs a thread, a socket, buffers and
> context switches. We paid for all of that and got nothing back.
>
> So: size the pool to the work. Not to the machine, and not to optimism.

---

## Slide 8 — Compose or combine · 75 seconds

> These two methods are the most commonly confused pair in the API, and there is one
> question that settles it every time: **does the next step need the previous step's
> answer?**
>
> On the left, yes. You cannot brew coffee before you have ground the beans. Each step
> returns its own future, and `thenCompose` flattens them into one chain. If you used
> `thenApply` here you would end up holding a `CompletableFuture` of a
> `CompletableFuture`, which is a real thing that happens to people.
>
> On the right, no. The croissant never needed the coffee. Those two futures were
> never related, so they run side by side and `thenCombine` joins them at the end.
> The pair costs the slower of the two — two and a half seconds — rather than the sum
> of both, which would be four.
>
> Need the answer? Compose. Never needed it? Combine.

---

## Slide 9 — Resilience · 75 seconds

> Asynchronous code fails in two different ways, and they need two different tools.
>
> The first is the one you expect: something throws. The future completes
> exceptionally, and `exceptionally` supplies a replacement value so the rest of the
> pipeline carries on. The customer still gets a drink.
>
> The second is more interesting. Sometimes the work simply never finishes. Nothing
> throws. There is no exception, so there is nothing for a `try`/`catch` to catch —
> it would sit there forever. `orTimeout` is what turns that silence into a
> `TimeoutException` you can actually act on.
>
> And `handle` runs on both paths, success and failure, so the closing summary gets
> written once instead of twice.
>
> The result is that one broken order never takes the others down with it, and the
> program never crashes.

---

## Slide 10 — The traps · 2 minutes

**This is the slide that shows you built something rather than read a tutorial.
Do not rush it.**

> Four things that will bite you, none of which the tutorials mention.
>
> First. If you call `supplyAsync` without passing an executor, it does not create a
> thread for you. It runs on the shared common ForkJoinPool — sized to your core
> count, shared with every parallel stream in the JVM, and designed for short
> CPU-bound work. Block it with I/O and you stall unrelated parts of your own
> program. Always pass your own pool.
>
> Second. `thenApply` makes no promise about which thread runs your callback. It runs
> on whichever thread happened to complete the future — or, if the future was already
> complete, on your calling thread, right there, synchronously. If you need to know,
> use the `Async` variants.
>
> Third. `allOf` waits for all of them, even after one has already failed. It
> completes exceptionally, but not early. In this project one chunk failed at
> sixty-two seconds and the join still did not return until a hundred and ten,
> because the surviving chunks were left to finish.
>
> Fourth. `orTimeout` gives up, but it does not cancel. Your future completes
> exceptionally and you move on, while the work underneath carries right on running
> and holding its thread. The timeout protects the caller, not the server.

---

## Slide 11 — Takeaways · 45 seconds

> Four things to take away.
>
> It buys waiting, not speed. It wins exactly where threads sit blocked on I/O, and
> it will never make your CPU faster.
>
> Partition, and you need no locks. Eight threads, eight offsets, one matching hash.
> Design it so the parallel parts cannot collide and you never have to synchronise them.
>
> Own every executor. Never put blocking work on the common pool, name your threads
> so you can read your own logs, size the pool to the work, and shut it down in a
> `finally`.
>
> And compose, but block only once, right at the end.
>
> That QR code is the demo. Scan it and race a file yourself — it runs fine on a
> phone. Happy to take questions.

**Leave this slide up.** People scanning the code while you answer questions is a
much better closing image than a bulleted summary.

---

## If something goes wrong

| Problem | What to do |
|---|---|
| The live page is slow or will not load | Go back to slide 6. Every number is on it. Say "the numbers are measured, here they are" and carry on. |
| Someone asks why the browser demo is slower than the slide | Browsers open only about six connections per origin on HTTP/1.1, so asking for sixteen ranges just queues them. The page says so itself when it detects it. Same lesson as slide 7. |
| Asked whether this works on any website | No. The speedup needs a server that limits each connection separately. Against a CDN that does not, the gain is close to nothing — measured at 1.1× against GitHub. That is a feature of the explanation, not a gap in it. |
| Asked about virtual threads | Fair question, out of scope for today. Short answer: virtual threads make blocking cheap; `CompletableFuture` is about composing dependent work. Different problems. |
