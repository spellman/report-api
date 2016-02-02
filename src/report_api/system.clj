(ns report-api.system
  (:require [com.stuartsierra.component :as component]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]
            [report-api.core :as ra]))

;; system
;; ============================================================================
(def order-datasets
  (let [cents->dollars #(/ (bigdec %) 100)
        base-path "resources/"]
    #{{:source "source-1"
       :path (str base-path "source_1.csv")
       :config {:delimiter ","
                :decimal-point "\\."
                :xforms [[:order-id str]
                         [:unit-price-dollars cents->dollars]
                         [:source-2-discount-dollars cents->dollars]
                         [:source-1-discount-dollars cents->dollars]
                         [:session-type str/lower-case]]}}
      {:source "source-2"
       :path (str base-path "source_2.psv")
       :config {:delimiter "\\|"
                :decimal-point "\\."
                :xforms [[:order-id str]
                         [:unit-price-dollars bigdec]
                         [:source-1-discount-dollars bigdec]
                         [:source-2-discount-dollars bigdec]
                         [:session-type str/lower-case]]}}}))

(def report-orderings-white-list
  #{[:session-type :desc]
    [:order-id :asc]
    [:unit-price-dollars :asc]})

(def summary-keys
  #{:unit-price-dollars
    :source-1-discount-dollars
    :source-2-discount-dollars})

(defn system
  "Returns a new instance of the whole application."
  [config-options]
  (let [{:keys [port]} config-options
        config-options (dissoc config-options :port)]
    (component/system-map
     :parser ra/parse-nice-dsv
     :processor (ra/->OrdersSummarizer ra/sum-keys summary-keys)
     :reporter ra/generate-report
     :web-app (component/using
               (ra/compare-order-data order-datasets
                                      report-orderings-white-list)
               [:parser
                :processor
                :reporter])
     :logger (ra/->TimbreLogger
              {:output-fn (partial taoensso.timbre/default-output-fn {:stacktrace-fonts {}})})
     :app (component/using
           (ra/web-server port)
           [:web-app
            :logger]))))
