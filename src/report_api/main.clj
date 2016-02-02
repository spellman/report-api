(ns report-api.main
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [environ.core :refer [env]]
            [taoensso.timbre :as timbre]
            [report-api.system :as system]))

;; Entry-point for production
;; ============================================================================
(defn -main [& [port]]
  ;; As per Stuart Sierra's blog post, when an exception is thrown
  ;; off the main thread and nothing catches the exception, the thread
  ;; dies silently.
  ;; Instead, I'll log the exception.
  ;; http://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
  ;; (Note that I've put the same thing in /dev/user.clj)
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread e]
       (timbre/error e "Uncaught exception on" (.getName thread)))))
  (let [port (Integer. (or port (env :port) 5000))]
    (component/start (system/system {:port port}))))
