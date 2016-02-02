(ns report-api.core-test
  (:require [com.stuartsierra.component :as component]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.core.async :as async :refer [<!! >!! chan]]
            [clj-http.client :as client]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [cheshire.parse :as parse]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [taoensso.timbre :as timbre]
            [report-api.system :as sys]
            [report-api.core :as ra])
  (:use clojure.test
        report-api.core))

;; API functionality
;; ============================================================================
(def test-port 3001)

(def report-route "/report")

(defrecord TestLogger [channel]
  Log
  (error [logger args] (>!! channel args)))

(defn test-logger []
  (->TestLogger (chan 32)))

(defrecord TestApp [web-app logger handler]
  component/Lifecycle
  (start [component]
    (if (:handler component)
      component
      (assoc component :handler (ra/make-handler web-app logger))))
  (stop [component]
    (let [handler (:handler component)]
      (if-not handler
        component
        (assoc component :handler nil)))))

(defn test-app []
  (component/using (map->TestApp {})
                   [:web-app
                    :logger]))

(defn test-system []
  (assoc (sys/system {})
         :logger (test-logger)
         :app (test-app)))

(defn make-json-string-parseable [json-str]
  (-> json-str
      (edn/read-string)
      (str/replace #"(?m)$\s*\n\n" "\n")
      (str/replace "{ \\" "{\\")))

(defn parse-expected-json [file-path]
  (-> file-path
      (slurp)
      (make-json-string-parseable)
      (json/parse-string true)))

(defn parse-json-response-body [response]
  (-> response :body (json/parse-string true)))

(defn number->bigdec [x]
  (if (number? x) (bigdec x) x))

(defn numbers->bigdec [x]
  (walk/postwalk number->bigdec x))

(defn json-response? [response]
  (re-find #"application/json"
           (get-in response [:headers "Content-Type"])))

(defn has-keys? [ks m]
  (every? (set (keys m)) ks))

;; Processable requests against the real system
;; ----------------------------------------------------------------------------
(defn test-report-output [port ordering]
  ;; NOTE: Can I use the core.async-based test-logger and check that nothing
  ;;       comes through on the error-logging channel?
  ;;       Options:
  ;;       1.  I guess I could read from either the error channel or a timeout
  ;;           channel that should be plenty long enough for an error to come
  ;;           through if one is coming.
  ;;           I DON'T LIKE THIS - it's guessing about how long the app will
  ;;           run.
  ;;       2.  Can I wrap the system such that response gets put on channel?
  ;;           Then I could take the first value available with alt!!, either
  ;;           the response or the error.
  ;;           I LIKE THIS - it has no guessing like option 1 does.
  (let [system (component/start (sys/system {:port port}))
        expected-output-dir "test/resources/expected-output"
        file (str expected-output-dir "/expected-report-order-by-" ordering ".json")
        expected-json (parse-expected-json file)
        url (str "http://localhost:" port (str report-route "?order_by=") ordering)
        response (client/get url)]
    (try
      (is (= 200 (:status response)))
      (is (json-response? response))
      (is (= (numbers->bigdec expected-json)
             (-> response parse-json-response-body numbers->bigdec)))
      (catch Throwable e (timbre/error e))
      (finally (component/stop system)))))

(deftest t-report-output-ordered-by-session-type-desc
  (test-report-output test-port "session-type-desc"))

(deftest t-report-output-ordered-by-order-id-asc
  (test-report-output test-port "order-id-asc"))

(deftest t-report-output-ordered-by-unit-price-dollars-asc
  (test-report-output test-port "unit-price-dollars-asc"))

;; Error responses with ring-mock
;; ----------------------------------------------------------------------------
(defn json-error-response? [handler url response-http-status]
  (let [request (mock/request :get url)
        response (handler request)]
    (and
     (= response-http-status (:status response))
     (json-response? response)
     (has-keys? [:error] (parse-json-response-body response)))))

(defn test-check-json-error-response [base-url url-pred response-http-status]
  (let [system (component/start (test-system))]
    (try
      (prop/for-all [s (gen/such-that url-pred gen/string-alphanumeric)]
                    (json-error-response? (get-in system [:app :handler])
                                          (str base-url s)
                                          response-http-status))
      (catch Throwable e (timbre/error e))
      (finally (component/stop system)))))

(defspec t-unsupported-url-yields-error-json
  100
  (test-check-json-error-response "/"
                                  #(not= report-route %)
                                  404))

(defspec t-unpermitted-order_by-param-yields-error-json
  100
  (let [permitted-order_by-params (set
                                   (map (fn [[k d]]
                                          (str (name k) "-" (name d)))
                                        sys/report-orderings-white-list))]
    (test-check-json-error-response (str report-route "?order_by=")
                                    (complement permitted-order_by-params)
                                    422)))

;; Exception-handling
;; ----------------------------------------------------------------------------
(defrecord ExceptionWebApp []
  ra/WebApp
  (run-web-app [web-app request]
    (throw (Exception. "deliberately thrown"))))

(deftest t-web-app-error-yields-error-json
  (let [system (component/start
                (assoc (test-system)
                       :web-app (->ExceptionWebApp)))
        {:keys [:logger]} system
        {:keys [:channel]} logger]
    (try
      (is (json-error-response? (get-in system [:app :handler])
                                report-route
                                500))
      (let [pr-str-of-throwable (first (<!! channel))]
        (is (re-find #":message \"deliberately thrown\""
                     pr-str-of-throwable)))
      (catch Throwable e (timbre/error e))
      (finally (component/stop system)))))



;; order_by URL parameter parsing
;; ============================================================================
(def hyphenless-string
  (gen/such-that #(not (re-find #"-" %)) gen/string))

(def empty-string
  (gen/elements [""]))

(deftest t-order_by-param-with-nil-value-is-parsed-to-empty-seq
  (is (= [] (#'report-api.core/parse-ordering nil))))

(defspec t-hyphenless-order_by-param-is-split-into-param-and-nil
  100
  (prop/for-all [s hyphenless-string]
                (let [[sort-key sort-direction] (#'report-api.core/parse-ordering s)]
                  (and
                   (= (keyword s) sort-key)
                   (= nil sort-direction)))))

(defspec t-order_by-param-with-at-least-one-hyphen-is-split-at-last-hyphen
  100
  (prop/for-all [v (gen/vector hyphenless-string)
                 d hyphenless-string] ; If d is empty, then o ends in a hyphen.
                (let [k (str/join "-" v)
                      o (str k "-" d)
                      [sort-key sort-direction] (#'report-api.core/parse-ordering o)]
                  (and
                   (= (keyword k) sort-key)
                   (= (keyword d) sort-direction)))))

;; orderset parsing
;; ============================================================================
(defn gen-dataset [escaped-delimiters]
  (gen/let [num-rows gen/s-pos-int
            num-cols gen/s-pos-int
            header (gen/vector-distinct
                    (gen/such-that (fn [x]
                                     (not-any? (fn [d] (re-find (re-pattern d) x))
                                               escaped-delimiters))
                                   (gen/fmap str gen/symbol))
                    {:num-elements num-cols})
            rows (gen/vector
                  (gen/vector
                   (gen/such-that (fn [x]
                                    (not-any? (fn [d]
                                                (and (string? x)
                                                     (re-find (re-pattern d) x)))
                                              escaped-delimiters))
                                  (gen/one-of
                                   [(gen/fmap str gen/symbol)
                                    gen/int]))
                   num-cols)
                  num-rows)]
    (into [header] rows)))


;; "Nice" DSV data
;; *   Neither column names nor record values may contain the delimiter.
;; *   All rows much have the same number of elements.
;; *   There may be other restrictions - it should look like the given datasets
;;     in /resources.
(defspec t-parsing-handles-various-delimiters
  10
  (let [delimiters [["," ","]
                    ["|" "\\|"]
                    [":" ":"]
                    [(str \tab) "\\t"]]
        d-strs (map first delimiters)
        d-escaped (map second delimiters)
        parse-fn ra/parse-nice-dsv]
    (prop/for-all [data (gen-dataset d-escaped)]
                  (let [source "source"
                        header-row (first data)
                        data-strings (map (fn [s]
                                            (->> data
                                                 (map (fn [row] (str/join s row)))
                                                 (str/join "\n")))
                                          d-strs)
                        parsed (map (fn [d ds]
                                      (let [config {:delimiter d
                                                    :decimal-point "\\."
                                                    :xforms (map (fn [col-name]
                                                                   [col-name identity])
                                                                 header-row)}]
                                        (parse-fn ds config source)))
                                    d-escaped
                                    data-strings)]
                    (apply = parsed)))))
