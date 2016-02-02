(ns dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application."
  (:require
    [clojure.java.io :as io]
    [clojure.java.javadoc :refer [javadoc]]
    [clojure.pprint :refer [pprint]]
    [clojure.reflect :refer [reflect]]
    [clojure.repl :refer [apropos dir doc find-doc pst source]]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :as test]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [clojure.tools.namespace.repl :refer [refresh refresh-all]]
    [clojure.java.classpath :as cp]
    [clojure.edn :as edn]
    [com.stuartsierra.component :as component]
    [clj-http.client :as client]
    [report-api.system :as sys]
    [report-api.core :as ra :refer :all]))

(def system
  "A Var containing an object representing the application under
  development."
  nil)

(defn init
  "Creates and initializes the system under development in the Var
  #'system."
  []
  (let [config-options {:port 3000}]
    (alter-var-root #'system
                    (constantly (sys/system config-options)))))

(defn start
  "Starts the system running, updates the Var #'system."
  []
  (alter-var-root #'system
                  component/start))

(defn stop
  "Stops the system if it is currently running, updates the Var
  #'system."
  []
  (alter-var-root #'system
                  #(when % (component/stop %))))

(defn go
  "Initializes and starts the system running."
  []
  (init)
  (start)
  :ready)

(defn reset
  "Stops the system, reloads modified source files, and restarts it."
  []
  (stop)
  (refresh :after `go))
