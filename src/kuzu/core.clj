(ns kuzu.core
  "Distributed computation built on top of nREPL.

  Specify remote servers using these keys:
  :name       an arbitrary name
  :host       hostname (e.g. IP address)
  :port       port number
  :client     return value from `repl/client`
  :connected  true or false
  :tasks      number of current tasks"
  (:refer-clojure :exclude (eval filter map reduce))
  (:require [clojure.core :as c]
            [clojure.tools.nrepl :as repl])
  (:import [java.net SocketException]))

(defn client
  "Returns an nREPL client"
  [host port timeout]
  (let [conn (repl/connect :host host :port port)]
    (repl/client conn timeout)))

(defn clients
  "Returns a map containing a key/value for each server config."
  [servers timeout]
  (let [f (fn [{:keys [name host port]}]
            [name {:name name
                   :host host
                   :port port
                   :client (client host port timeout)
                   :connected true
                   :tasks 0}])]
    (into {} (c/map f servers))))

(defn connected-clients
  "Returns a sequence of maps, each representing a client."
  [clients]
  (c/filter :connected (vals clients)))

(defn available-clients
  "Returns a sequence of maps, each representing a client.
  A client is considered available when its current task
  count is below the concurrency threshold `conc`."
  [clients conc]
  (->> (connected-clients clients)
       (c/filter #(< (:tasks %) conc))))

(defn long-poll
  "Returns (f) if (pred (f)) returns true value before `max-i`
  iterations pass. The first iteration waits `i-delay` ms. Each
  following iteration waits x times the prior delay. (This is
  exponential backoff.) Raises an exception with failure message
  on time-out."
  [f pred max-i i-delay x fail-msg]
  {:pre [(> x 1)]}
  (loop [i 0 delay i-delay]
    (if (>= i max-i)
      (throw (ex-info fail-msg {:delay (* max-i i-delay)}))
      (let [r (f)]
        (if (pred r)
          r
          (do
            (Thread/sleep delay)
            (recur (inc i) (int (* x delay)))))))))

(defn available-client
  "Returns a map, representing a random available client with the
  fewest active tasks, based on concurrency limit `conc`. If none
  available, sleeps and tries again, up to a limit."
  [clients conc]
  (let [f #(available-clients clients conc)]
    (->> (long-poll f seq 10 100 1.25 "No available client found")
         (shuffle)
         (sort-by :tasks)
         (first))))

(defn eval
  "Evaluates the form data structure (not text!) on an available
  client based on the concurrency limit `conc`. Blocks for up to
  `timeout` milliseconds and returns the result on success or throws
  an exception."
  [clients-atom conc form]
  (let [client (available-client @clients-atom conc)
        name (:name client)]
    (try
      (swap! clients-atom update-in [name :tasks] inc)
      (let [responses (-> (:client client)
                          (repl/message {:op :eval :code (pr-str form)}))
            result (repl/response-values responses)]
        (swap! clients-atom update-in [name :tasks] dec)
        (if result
          (first result)
          (throw (ex-info "Evaluation failed"
                          {:responses (repl/combine-responses responses)}))))
      (catch SocketException e
        (swap! clients-atom update-in [name] merge {:client nil
                                                    :connected false
                                                    :tasks 0})
        (eval clients-atom conc form)))))

(defn reconnect
  "Reconnect to server with given name."
  [clients-atom name timeout]
  (let [c (get @clients-atom name)
        client (client (:host c) (:port c) timeout)]
    (swap! clients-atom update-in [name] merge {:client client
                                                :connected true
                                                :tasks 0})))

(defn map
  "Same as map (returns a lazy seq) but distributes the work to
  clients according to chunk size `n` and concurrency `c`.
  Important: `f` must be quoted."
  [clients-atom c n f coll]
  (let [k (* c (count (available-clients @clients-atom c)))
        e #(eval clients-atom c `(c/map ~f '~%))
        rets (->> (partition-all n coll)
                  (c/map #(future (e %))))
        step (fn step [[x & xs :as vs] fs]
               (lazy-seq
                 (if-let [s (seq fs)]
                   (concat (deref x) (step xs (rest s)))
                   (c/mapcat deref vs))))]
    (step rets (drop k rets))))

(defn filter
  "Same as filter (returns a lazy seq) but distributes the work to
  clients according to concurrency `c` and chunk size `n`.
  Important: `f` must be quoted."
  [clients-atom c n f coll]
  (let [k (* c (count (available-clients @clients-atom c)))
        e #(eval clients-atom c `(c/filter ~f '~%))
        rets (->> (partition-all n coll)
                  (c/map #(future (e %))))
        step (fn step [[x & xs :as vs] fs]
               (lazy-seq
                 (if-let [s (seq fs)]
                   (concat (deref x) (step xs (rest s)))
                   (c/mapcat deref vs))))]
    (step rets (drop k rets))))

(defn reduce
  "Same as reduce (returns one value) but distributes the work to
  clients according to chunk size `n` and concurrency `c`.
  Important: `f` must be quoted."
  [clients-atom c n f coll]
  (let [e #(eval clients-atom c `(c/reduce ~f '~%))]
    (->> (partition-all n coll)
         (c/map e)
         (c/reduce (c/eval f)))))
