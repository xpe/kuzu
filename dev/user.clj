(ns user
  (:require
    [clojure.pprint :refer (pprint)]
    [clojure.repl :refer :all]
    [clojure.tools.namespace.repl :refer (refresh)]
    [clojure.tools.nrepl :as repl]
    [kuzu.core :as k]))

(def clients (atom []))

(def config
  {:timeout 20000
   :servers [{:name "1"
              :host "127.0.0.1"
              :port 52001}
             {:name "2"
              :host "127.0.0.1"
              :port 52002}
             {:name "3"
              :host "127.0.0.1"
              :port 52003}]})

(defn init
  "Initializes the system and returns nil."
  []
  (let [servers (:servers config)
        timeout (:timeout config)]
    (reset! clients (k/clients servers timeout))
    nil))
