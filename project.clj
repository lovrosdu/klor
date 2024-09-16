(defproject klor "0.1.0-SNAPSHOT"
  :description "Choreographies in Clojure"
  :url "https://github.com/lovrosdu/klor"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[com.taoensso/nippy "3.3.0"]
                 [criterium/criterium "0.4.6"]
                 [dorothy/dorothy "0.0.7"]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.681"]
                 [org.clojure/core.match "1.1.0"]
                 [org.clojure/tools.analyzer.jvm "1.3.0"]
                 [potemkin/potemkin "0.4.6"]]
  :repl-options {:init-ns klor.examples})
