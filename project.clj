(defproject kuzu "0.1.0-SNAPSHOT"
  :description "Simple distributed computation in Clojure on top of nREPL"
  :url "https://github.com/bluemont/kuzu"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.nrepl "0.2.3"]]
  :profiles {:dev
             {:source-paths ["dev"]
              :dependencies [[org.clojure/tools.namespace "0.2.4"]
                             [org.clojure/java.classpath "0.2.2"]]}})
