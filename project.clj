(defproject klor "0.1.0-SNAPSHOT"
  :description "Choreographies in Clojure"
  :url "http://github.com/lovrosdu/klor"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.681"]
                 [metabox/metabox "0.2.0"]
                 [mvxcvi/puget "1.3.4"]]
  :main klor.core
  :repl-options {:init-ns klor.core})
