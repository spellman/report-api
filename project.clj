(defproject report-api "0.1.0-SNAPSHOT"
  :description "Project to explore building a Ring app and Stuart Sierra's Component framework and Reloaded workflow."
  :plugins [[lein-environ "1.0.1"]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.stuartsierra/component "0.3.1"]
                 [ring "1.4.0"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-json "0.4.0"]
                 [compojure "1.4.0"]
                 [environ "1.0.1"]
                 [com.taoensso/timbre "4.2.0"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [org.clojure/test.check "0.9.0"]
                                  [org.clojure/core.async "0.2.374"]
                                  [clj-http "2.0.0"]
                                  [ring/ring-mock "0.3.0"]
                                  [cheshire "5.5.0"]]
                   :source-paths ["dev"]}
             :uberjar {:aot :all}}
  :uberjar-name "report-api-standalone.jar"
  :repl-options {:init-ns user}
  :main report-api.main
  :min-lein-version "2.0.0")
