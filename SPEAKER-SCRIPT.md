# Speaker script

The whole talk, written out. Read it as it stands if you like — it is meant to sound
like a person talking, not a paper being recited.

**About 12 minutes**, roughly 3 of which is the live demo. Every number is printed on
a slide, so if the network fails you keep going and nobody notices.

Before you start: have `concurrency-demo.onrender.com` open in a browser tab. These
lines are also in the deck's speaker notes (PowerPoint: *View → Notes Page*).

---

## 1 · Title — 20 seconds

> Read the title as a sentence: how a program waits for many things at once. That is
> the whole subject of the next ten minutes.
>
> I am going to define every word before I use it, because most of these words get
> thrown around loosely and that is where the confusion usually starts.

---

## 2 · Four words — 2 minutes

**Slow down here. Everything later rests on this slide.**

> A **thread** is one worker inside your program. It carries out one instruction at a
> time, in order. When you write a plain Java program, you get exactly one of these.
>
> **Blocking** is when that worker asks for something slow — a file, a reply from the
> internet — and simply stops until the answer comes back. It is still holding its
> memory. It is still occupying a place in the system. And it is doing no work at all.
>
> **Concurrency** is several pieces of work being underway at the same time. And I want
> to be careful with this one, because it is the one people mishear: this is *not* the
> same as doing more calculation. Usually it just means more *waiting* happening at once.
>
> **Asynchronous** means that instead of stopping to wait for an answer, you say in
> advance what should be done when the answer arrives, and you carry on with something
> else in the meantime.
>
> And **CompletableFuture** is the Java class that does that last one. It is a container
> for a value that has not arrived yet, together with the plan for what to do once it
> has. It arrived in Java 8.

---

## 3 · The problem — 90 seconds

> Here is an ordinary piece of work. We are downloading a nine megabyte file over one
> connection, from a server that gives each connection about four tenths of a megabyte
> per second. It takes twenty-one seconds.
>
> Now look at what the machine is doing for those twenty-one seconds.
>
> Zero percent of the processor. The thread is parked inside a system call, waiting for
> bytes. It is holding about a megabyte of memory the whole time and using none of it.
>
> This is the part that gets misdiagnosed. When something takes twenty-one seconds the
> instinct is to reach for a faster machine — but there is no calculation happening here
> to speed up. The processor is already idle.
>
> **This was never a CPU problem, so it cannot have a CPU answer.**

*Pause here if you pause anywhere.*

---

## 4 · What it is — 2 minutes

> So what do we do instead? We say: hand me an empty box now, and I will tell you what
> to do when it fills.
>
> Normally you ask for a value and your thread stops until it is handed one. That
> stopping is exactly the waste we just measured.
>
> A CompletableFuture is handed to you straight away, and it is empty. The work to fill
> it is going on somewhere else, on another thread.
>
> And then you write down what should happen to the value once it turns up. Change it.
> Join it with another one. Recover if it fails.
>
> None of that has run yet. You are writing instructions about a value you do not have.
> Only right at the end do you actually ask for the answer — and by then it is usually
> already sitting there waiting for you.
>
> The change is from writing *"wait here, then do this"* to writing *"when this lands,
> do this"*. The thread stays free the whole way through, which means it can be handed
> somebody else's work instead of standing idle.

---

## 5 · Using it — 2 minutes *(then the demo)*

> Here is that applied to the download.
>
> We cut the file into eight byte ranges, and we ask for all eight at the same moment.
> That is `supplyAsync`, once per range.
>
> `allOf` finishes when every one of them has finished, and `thenApply` turns that into
> the completed file.
>
> The detail that makes it safe is this: the ranges never overlap, and the file is
> allocated up front, so each range writes into its own place. Nothing is shared, so
> nothing needs a lock — there is no `synchronized` anywhere in this code.
>
> One connection: twenty-one seconds. Eight connections: two point six. Eight times
> faster. And both files have the same checksum, so not one byte landed in the wrong
> place.
>
> The graph on the right answers the obvious next question — if eight is good, why not
> eight hundred? It is linear up to eight, then completely flat. Past that the limit is
> total bandwidth, and every extra connection costs a thread and a socket while buying
> nothing.

**→ Switch to the browser.** Let the room pick a file from the library on the left,
press *Download both ways*, and talk over it:

> Left is one connection, right is eight. Watch the clocks. Both sides end up with
> their own copy that you can open — same picture, same checksum. One just arrived
> about eighteen seconds earlier.

---

## 6 · Joining steps — 90 seconds

> These two methods are the most confused pair in the API, and one question separates
> them: **does the next step need the answer from the one before it?**
>
> On the left, yes. You cannot brew before you grind. Each step hands back its own box,
> and `thenCompose` flattens them into a single chain.
>
> On the right, no. The pastry never needed the coffee. Those two were already running
> side by side, and `thenCombine` joins them at the end. So the pair costs the slower of
> the two — two and a half seconds — instead of the two added together, which would be
> four.
>
> Needs the answer? Compose. Never needed it? Combine.

---

## 7 · When it goes wrong — 90 seconds

> Asynchronous work fails in two different ways, and they need different tools.
>
> The first is the one you expect. Something throws an error. `exceptionally` supplies a
> replacement value so the rest of the chain carries on instead of collapsing.
>
> The second is more interesting. Sometimes the work simply never finishes. Nothing is
> thrown. There is no error to catch, so a `try`/`catch` would sit there forever.
> `orTimeout` is what turns that silence into a timeout you can actually respond to.
>
> And `handle` runs on both paths, success and failure, so the tidying-up gets written
> once rather than twice.
>
> The box at the bottom is the trap I would most want you to remember. If you call
> `supplyAsync` and do not give it a thread pool, it does not make one for you. It
> borrows the single pool the entire JVM shares — which is built for short calculations.
> Block that with a download and you stall unrelated parts of your own program.

---

## 8 · Takeaways — 45 seconds

> Four things to take away.
>
> It buys waiting, not speed. It wins where threads sit blocked on input and output, and
> it will never make your processor faster.
>
> Split the work and you need no locks. Eight threads, eight separate places to write,
> one matching checksum.
>
> Own every thread pool you use. Never put blocking work on the shared one.
>
> And describe first, wait once, at the end.
>
> That QR code is the live demo — scan it and download a file yourself, it works fine on
> a phone. Happy to take questions.

**Leave this slide up** while you answer.

---

## If something goes wrong

| Problem | What to say |
|---|---|
| Page slow or will not load | Go back to slide 5; every number is printed on it. "The numbers are measured, here they are." |
| Browser demo slower than the slide | Browsers open only about six connections per site, so asking for sixteen ranges just queues the extras. The page says so when it detects it — and it is the same lesson as the flat part of the graph. |
| "Does this work on any website?" | No. It needs a server that limits each connection separately. Against a CDN that does not, the gain is almost nothing — measured at 1.1× against GitHub. |
| "What about virtual threads?" | Out of scope today. Short answer: virtual threads make blocking cheap; CompletableFuture is about composing dependent work. Different problems. |
| "Why is it faster if the CPU was idle?" | Because the limit was never the CPU. One connection gets a fixed share of bandwidth; eight connections get eight shares. |
