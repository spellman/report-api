(ns user
  (:require [taoensso.timbre :as timbre]))

;; As per Stuart Sierra's blog post, when an exception is thrown
;; off the main thread and nothing catches the exception, the thread
;; dies silently.
;; Instead, I'll log the exception.
;; http://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
;; (Note that I've put the same thing in /src/report_api/main.clj)
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread e]
     (timbre/error e "Uncaught exception on" (.getName thread)))))

(defn dev
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev)
  (in-ns 'dev))
