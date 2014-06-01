# Kuzu

Kuzu offers simple distributed computation in Clojure. It uses nREPL under the
hood. (That said, there isn't really much hood to look under.)

With Kuzu:

  * use Clojure functions for distributed eval, map, filter, reduce
  * you don't need Hadoop or Storm
  * you don't need to package and move JAR files around
  * you don't have to wait long for work to be done
  * bring your own process supervision and dev-ops, if you want

Kuzu provides these functions:

  * `eval` remote evaluation (blocking)
  * `map` distributed mapping (blocking, lazy)
  * `filter`: distributed filtering (blocking, lazy)
  * `reduce`: distributed reducing (blocking)

If you want non-blocking, asynchronous behavior, it is not hard to roll your
own. (Patches are also welcome.)

Kuzu will notice if a server is disconnected, but it does not manage servers
or JVM's. It is your responsibility to supervise and restart these servers as
needed. Once you have restarted a server, use `kuzu.core/reconnect` to, well,
reconnect to it.

## Examples

Let's walkthrough some example code to show how Kuzu works.

I'm going to skip server setup, since that's up to you.

### Initialize

Start 3 local REPLs to act as servers:

```sh
lein repl :start :port 52001
lein repl :start :port 52002
lein repl :start :port 52003
```

Start a REPL for interactive input from inside the `kuzu` directory. This will
setup `k` to alias to `kuzu.core`, thanks to `dev/user.clj`.

```sh
lein repl
```

Note: in the explanation below, I refer to the above servers as 'clients',
since they act as nREPL clients from the perspective of the interactive REPL.

Initialize the connections to the clients:

```clj
(init)
(pprint @clients)
```

Here are the essential parts from `dev/user.clj`:

```clj
(def clients (atom []))

(def config
  {:timeout 20000
   :servers [{:name "1" :host "127.0.0.1" :port 52001}
             {:name "2" :host "127.0.0.1" :port 52002}
             {:name "3" :host "127.0.0.1" :port 52003}]})

(defn init []
  (reset! clients (k/clients (:servers config) (:timeout config))))
```

Remotely-run code must finish within the specified timeout or an exception
will be thrown.

### Connected, But Unique

Ok, now your interactive REPL is connected to the remote REPLs. That said, the
REPLs are *not* sharing a JVM. As you would expect, each server has its own
memory space. Keep this in mind as you walk through the examples below.

### Eval

Run code on the clients (i.e. remote servers) using `eval`, which will run the
code on a remote client on each invocation.

```clj
(k/eval clients 5 `(reduce + (range 100)))
; repeat to see how it uses different servers, somewhat randomly
```

You'll need to repeat a few times, because Kuzu chooses clients based on which
is least busy, with ties broken by randomness.

Just like `clojure.core/eval`, you need to quote the form you pass to
`kuzu.core/eval`. Quoting is always needed in Kuzu, because the form is
serialized using `pr-str` and sent to the remote servers via nREPL.

### Recovery After Failure

Kuzu can recover if a server fails in the middle of a computation. It marks
that server as disconnected and then tries another.

To see this in action, kill off server #1. Press CTRL-D or type `(exit)` or
`(quit)`.

In the interactive REPL, see how Kuzu recovers:

```clj
(k/eval clients 5 `(reduce + (range 100)))
; Repeat until you see how it notices the failure and stops using
; the disconnected server.
```

Restart server #1:

```sh
lein repl :start :port 52001
```

In the interactive REPL, reconnect to server #1:

```clj
(k/reconnect clients "1" 20000)
```

Note: the last argument to `reconnect`, 20000 milliseconds, is arbitrary.
Whatever code you run remotely needs to finish within that timeout or an
exception will be thrown.

```clj
(k/eval clients `(reduce + (range 100)))
; Repeat until you see that the reconnected server being used.
```

### Mapping

First, a basic example:

```clj
(init)
(def xs (range 1000))
(def ys (k/map clients 5 1000 `inc xs))
(last ys) ; 1000
```

As you would expect, this gives the same result as `(map inc xs)`. The key
difference in syntax is that you must quote the function; this is always the
case with Kuzu.

In the example above:

  * Each client is allowed a concurrency of `5`, meaning that it can work `5`
    tasks on the same JVM.
  * The "unit of work" (e.g. chunk size) is `100` items, meaning that `xs` is
    partitioned into chunks containing `100` elements. (Note: "chunk size", as
    used here, is not the same as Clojure's internal chunked sequences.)

You might be interested in seeing how different choices for concurrency and
chunk size affect the response times:

```clj
(init)
(def xs (range 300))
(def q `#(do (Thread/sleep 100) %))
(defn t [c n] (time (doall (k/map clients c n q xs))))
```

The code below shows 5 samples for each [c, n] combination:

```clj
(t   2 60) ; 6022 6024 6030 6031 6035
(t   3 30) ; 3020 3021 3025 3027 3031
(t   6 30) ; 3023 3024 3028 3030 3031
(t   6 15) ; 1521 1522 1523 1525 1527
(t  10 10) ; 1023 1024 1024 1025 1026
(t  20  5) ;  531  532  532  532  533
(t  50  2) ;  270  270  271  272  273
(t 100  1) ;  238  239  250  252  258
(t 150  1) ;  238  258  258  262  306
(t 300  1) ;  262  262  263  274  281
(t 400  1) ;  241  254  259  261  262
```

In the example above, `(t 100  1)` offers low response time without unneeded
concurrency.

### Filtering

First, a basic example:

```clj
(init)
(def xs (range 1000))
(def ys (k/filter clients 5 1000 `even? xs))
(last ys) ; 998
```

This gives the same result as running `(filter even? xs)`.

To see how different choices for concurrency and chunk size affect the
response times:

```clj
(init)
(def xs (range 1000))
(def q `#(= (mod % 5) (mod (inc %) 7)))
(defn t [c n] (time (doall (k/filter clients c n q xs))))
```

Here are some example runs with elapsed times (in ms):

```clj
(t 20  25) ; 35.832
(t 10  50) ; 21.425
(t  5 100) ;  9.631
(t  1 500) ;  6.806
```

If you choose your settings unwisely (e.g. try running `(t 2 10)`) then you
may get this exception:

> ExceptionInfo No available client found  clojure.core/ex-info (core.clj:4403)

### Reducing

To try out `reduce`:

```clj
(init)
(def xs (range 1000))
(k/reduce clients 5 100 `+ xs) ; 499500
```

This particular example is not compelling, since addition is not
computationally intensive enough to warrant the overhead of distributing the
work. In this case, `(reduce + xs)` performs the same work more quickly.

## Miscellaneous

Here are some tips and tricks that may be useful.

### Syntax Quoting and Unquoting

You may want to remotely evaluate code without specifying the entirety of it
on one line. For example:

```clj
(init)
(def xs (range 10))
(k/eval clients `(map inc '~xs))
```

Note that `xs` is not defined in the remote REPLs. The example above
assembles a form and sends it to the remote server for evaluation.

### Show Remote Task Counts

To show the number of tasks currently running on each nREPL server, use code
something like this:

```
(map :tasks (vals @clients))
```

This code works in the context of the examples above. Note: this is
`clojure.core/map`, not `kuzu.core/map`.

A task is defined as one unit of work initiated by `kuzu.core/eval`.

### Production

For production deployments, you probably will want to start a headless REPL
under process supervision. You also may want to lock down and/or encrypt
traffic to the nREPL ports.

Note: I have not run Kuzu in production yet, but I expect that it will creep
it way into my servers soon enough.

### Source Code Notes

The source code of `kuzu.core/map` is based on `clojure.core/pmap` with only
some small modifications.

As people often say, and sometimes actually mean, I would appreciate your
feedback on the source code.

### Tests, Where Art Thou?

Currently, the README is the test suite. Shamefully, I have not taken the time
to figure out how to create an automated test suite.

### Name

This project is named after 'kuzu', a more accurate spelling of [kudzo], the
"climbing, coiling, trailing, perennial vines native to much of eastern Asia,
southeast Asia, and some Pacific Islands." In other habitats, kudzo is often
considered invasive.

[kudzo]: http://en.wikipedia.org/wiki/Kudzu

## License

Copyright 2014 Bluemont Labs LLC
