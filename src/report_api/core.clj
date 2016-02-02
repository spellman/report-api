(ns report-api.core
  (:require [com.stuartsierra.component :as component]
            [ring.middleware.defaults :refer :all]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response]
            [ring.middleware.json :refer :all]
            [taoensso.timbre :as timbre]
            [compojure.core :refer [GET defroutes]]
            [compojure.route :refer [not-found]]
            [clojure.string :as str]))

;; parser component
;; ============================================================================
(defn- data-in-canonical-form? [d]
  (and (sequential? d)
       (every? map? d)
       (apply = (map (comp set keys) d))))

(defn- str->records [s d]
  (->> s
       (str/split-lines)
       (rest)
       (map #(remove empty? (str/split % (re-pattern d))))))

(defn parse-nice-dsv
  "Parses tabular data in delimiter-separated-values format, where:
    *   The first line is a row of column headers,
    *   The delimiter character does not appear in any of the column headers or
        values.

    Takes arguments:
    *   data-str is the string to parse
    *   config is a map with keys:
        *   :delimiter-re : a regular expression that matches the delimiter
            character
        *   :decimal-point : a string to be used in constructing a regular
            expression with re-pattern, either \"\\.\" or \",\"
        *   :xforms : a seq of pairs, corresponding to the columns in the data
            to be parsed: [[<canonical key> <value transform-fn>]], where
            *   <canonical key> is the canonical name of the column header
                E.g., :unit-price-dollars could be the canonical key matching
                      a given file's column header of, say, \"Unit Price Cents\"
            *   <value transform-fn> is the function to transform values
                associated with the column header into the canonical form used by
                the program.
                E.g., (fn [v] (/ (bigdec v) 100)) would transform a number
                representing cents into dollars.
    *   source is a string representing the source of the data, which will be
        included in the parsing output

    Returns a map in the format
    {:source <source>
     :data [{<canonical key> <canonical value>}]}"
  [data-str config source]
  {:pre [(string? data-str)
         (string? (:delimiter config))]
   :post [(data-in-canonical-form? (:data %))]}
  (let [{d :delimiter xfs :xforms pt :decimal-point} config
        records (str->records data-str d)
        canonical-ks (map first xfs)
        val-xfs (map second xfs)
        canonicalized-data (map (fn [r]
                                  (->> r
                                       (map (fn [xf v] (xf v)) val-xfs)
                                       (zipmap canonical-ks)))
                                records)]
    {:source source
     :data canonicalized-data}))



;; processor component for summarizing data
;; ============================================================================
(defprotocol ProcessData
  (process [processor orders]))

(defrecord OrdersSummarizer [summary-fn summary-keys])

(extend OrdersSummarizer
  ProcessData
  {:process (fn [summarizer orders]
              {:pre [(-> orders :data data-in-canonical-form?)]
               :post [(-> % :data data-in-canonical-form?)]}
              (assoc orders :summary
                     ((:summary-fn summarizer) (:summary-keys summarizer) (:data orders))))})

(defn sum-keys [summary-keys orders]
  {:pre [(every? number? (for [k summary-keys o orders] (k o)))]}
  (let [init-val 0]
    (into {}
          (map (fn [k] [k (transduce (map k) + init-val orders)])
               summary-keys))))



;; reporter component
;; ============================================================================
(defn- combine-order-sets [group-by-key order-sets]
  (let [summaries (into {}
                        (map (fn [oset]
                               [(keyword (str (:source oset) "-summary"))
                                (:summary oset)])
                             order-sets))
        orders (->> (for [oset order-sets
                          o (:data oset)]
                      [(keyword (str (:source oset) "-data")) o])
                    (group-by (fn [[_ {group-by-val group-by-key}]] group-by-val))
                    (vals)
                    (map (partial into {})))]
    {:summaries summaries :orders orders}))

(defn- sort-direction [s]
  (case s
    :asc compare
    :desc (comp - compare)))

(defn- order-group->key-seq
  "Takes a key-fn and a direction, either :asc or :desc, and returns a fn that
  takes a map of orders (an element of the data seq in the map of combined
  orders, {:summaries {}, :data [{:source1 {<order>}, :source2 {<order}}]})
  and returns a seq of the value of the key in each order, sorted according
  to the given direction.

  Example:
  (order-group->key-seq :unit-price-dollars :asc) returns a fn that maps
     {:source1 {:order-id 1
                :unit-price-dollars 2}
      :source2 {:order-id 1
                :unit-price-dollars 1}}
  to [1 2]

  NOTE: Sorting each order according to the sort-direction means that the
        lexicographical sort that uses the result of applying the returned
        closure will order the order-groups by the smallest successive min
        for ascending order and by the largest successive max for descending
        order.
        By changing the sort below, this could be changed to, for example,
        the smallest successive max for ascending order and the largest
        successive min for descending order."
  [key-fn direction]
  (fn [orders]
    (->> orders
         vals
         (map key-fn)
         (remove nil?)
         (sort (sort-direction direction)))))

(defn- lex-compare
  "Takes a comparison fn, such as clojure.core/compare, and returns a fn that
  takes two sequences, s1 and s2, and compares them lexicographically, returning
  *   -1 if s1 < s2
  *    0 if s1 = s2
  *    1 if s1 > s2

  Examples:
  [1 2] [1 2] =>  0 because all elmts equal
  [1 1] [1 2] => -1 because 1st elmts equal, 2nd elmts (compare 1 2) => -1
  [1 2] [1 1] =>  1 because 1st elmts equal, 2nd elmts (compare 1 2) =>  1"
  [comp-fn]
  (fn [s1 s2]
    (let [[h1 & r1] s1
          [h2 & r2] s2
          comp-val (comp-fn h1 h2)]
      (cond (and (nil? h1) (nil? h2)) 0
            (or (neg? comp-val) (pos? comp-val)) comp-val
            :else (recur r1 r2)))))

(defn lex-sort
  "Sorts a sequence of collections lexicographically.
  Takes a sequence of collections, a key-fn that will be applied to each
  collection, and a sort direction, either :asc or :desc."
  [s key-fn direction]
  (sort-by key-fn
           (lex-compare (sort-direction direction))
           s))

(defn- sort-data [key-fn direction data]
  (update data :orders
          lex-sort (order-group->key-seq key-fn direction) direction))

(defn- ordering-pair? [o]
  (and (sequential? o)
       (let [[k d] o]
         (or (and (#{1 2} (count o))
                  (keyword? k)
                  (or (keyword? d)
                      (nil? d)))
             (empty? o)))))

(defn sorted-by? [coll f]
  (let [pairs (partition 2 1 coll)]
    (every? (fn [[a b]] (<= (f a b) 0)) pairs)))

(defn- order-groups-sorted-by? [order-groups key-fn direction]
  (let [colls (map (order-group->key-seq key-fn direction) order-groups)
        lex-comp-fn (lex-compare (sort-direction direction))]
    (sorted-by? colls lex-comp-fn)))

(defn generate-report
  "Returns a map with summaries of given order-sets and a list of the orders,
  tagged by order-set source. Summaries are maps of summary-keys to sums of the
  values associated with those keys in the order-sets.

  Takes arguments:
  *   sort-ordering : a seq of sort-key and sort-direction (:asc or :desc)
  *   order-sets from which to generate the report, in the format
  {:source <source name>
   :data [{<canonical key> <canonical value>}]}"
  [sort-ordering order-sets]
  {:pre [(ordering-pair? sort-ordering)
         (every? (comp data-in-canonical-form? :data) order-sets)]
   :post [(apply order-groups-sorted-by? (:orders %) sort-ordering)]}
  (let [[sort-key sort-direction] sort-ordering]
    (->> order-sets
         (combine-order-sets :order-id)
         (sort-data sort-key sort-direction))))



;; web-app component for making reports
;; ============================================================================
(defprotocol WebApp
  (run-web-app [web-app request]))

(defn- parse-ordering
  "Parse the value of the order_by parameter from a URL.
  The idea is to split the string at the last hyphen:
  nil                 => []
  <empty string>      => [: nil]
  a                   => [:a nil]
  a-                  => [:a :]
  a-a                 => [:a :a]
  session-type-desc   => [:session-type :desc]
  session-type-desc-  => [:session-type-desc :]"
  [ordering-str]
  {:pre [(or (nil? ordering-str) (string? ordering-str))]
   :post [(ordering-pair? %)]}
  (if (nil? ordering-str)
    []
    (let [o (str/split ordering-str #"-" -1)
          ;; NOTE: I hard-coded the regex for now because separating words
          ;; with - seems well-established for URLs.
          ;;
          ;; Limit of -1 for str/split means trailing empty strings are kept.
          ;; Omitting the limit params yields a default value of 0, which
          ;; discards trailing empty strings, which gives rise to more
          ;; special cases.
          [k d] (if (= 1 (count o))
                  [o]
                  [(butlast o) (last o)])
          sort-key (->> k (str/join "-") keyword)
          sort-direction (keyword d)]
      [sort-key sort-direction])))

(defn- unpermitted-ordering-error-reponse [ordering-str uri]
  (let [template (fn [input-scenario-str]
                   {:error "Invalid API request."
                    :description (str input-scenario-str
                                      "The following orderings are supported: \n"
                                      (str/join ", "
                                                ["session-type-desc"
                                                 "order-id-asc"
                                                 "unit-price-dollars-asc"]))})
        body (if (nil? ordering-str)
               (template
                (str "An order_by query parameter in the URL is required. \n"
                     "E.g., " uri "?order_by=session-type-desc \n"))
               (template
                (str "The ordering order_by=" ordering-str " is not supported. \n")))]
    (-> body
        (response/response)
        (response/status 422))))

(defrecord CompareOrderData
    [order-datasets permitted-orderings parser processor reporter]
  WebApp
  (run-web-app [web-app {:keys [ordering-str uri]}]
    (let [ordering (parse-ordering ordering-str)]
      (if-not (contains? permitted-orderings ordering)
        (unpermitted-ordering-error-reponse ordering-str uri)
        (->> order-datasets
             (map #(parser (slurp (:path %)) (:config %) (:source %)))
             (map (partial process processor))
             (reporter ordering)
             response/response)))))

(defn compare-order-data [order-datasets permitted-orderings]
  (map->CompareOrderData {:order-datasets order-datasets
                          :permitted-orderings permitted-orderings}))



;; logger component
;; ============================================================================
(defprotocol Log
  (debug [logger args])
  (info [logger args])
  (warn [logger args])
  (error [logger args])
  (fatal [logger args]))

;; Timbre uses macros for logging. (As does clojure.tools.logging.)
;; I want to use apply in logging protocol methods because protocol methods
;; can't be variadic as of Clojure 1.7.
;; Apply can't be used with macros. Therefore, I'm using this functionize macro
;; to call the logging macros from functions.
;; (See http://stackoverflow.com/a/9273469)
(defmacro functionize [macro]
  `(fn [& args#] (eval (cons '~macro args#))))

(defrecord TimbreLogger [config]
  component/Lifecycle
  (start [component]
    (taoensso.timbre/merge-config! config)
    component)
  (stop [component] component)

  Log
  (debug [logger args] (apply (functionize taoensso.timbre/debug) args))
  (info [logger args] (apply (functionize taoensso.timbre/info) args))
  (warn [logger args] (apply (functionize taoensso.timbre/warn) args))
  (error [logger args] (apply (functionize taoensso.timbre/error) args))
  (fatal [logger args] (apply (functionize taoensso.timbre/fatal) args)))



;; ring handler
;; ============================================================================
(def ^:private not-found-response
  (response/response
   {:error "Invalid API request."
    :description (str "This URL is not supported. \n"
                      "The following routes are supported: \n"
                      "http://<base-url>/report?order_by=<ordering>"
                      ", where <ordering> is one of "
                      (str/join ", "
                                ["session-type-desc"
                                 "order-id-asc"
                                 "unit-price-dollars-asc"]))}))

(defroutes routes
  (GET "/report"
       {{order_by :order_by} :params, uri :uri, web-app :web-app}
       (run-web-app web-app {:ordering-str order_by :uri uri}))
  (not-found not-found-response))

(defn- wrap-component
  "Ring middleware to assoc a component into the request in order to make the
  component available to the various route handlers."
  [handler k component]
  (fn [request]
    (handler (assoc request k component))))

(defn- wrap-error-handling [handler logger]
  (fn [request]
    (try
      (handler request)
      (catch Throwable e
        (let [description (str (.getMessage e) "\n This error has been logged.")]
          (error logger [(prn-str e)])
          (-> {:error "Internal error."
               :description description}
              (response/response)
              (response/status 500)))))))

(defn make-handler
  "Make a ring handler. Accepts a web-app component to be injected into the
  functions that actually handle requests."
  [web-app logger]
  (-> #'routes
      (wrap-component :web-app web-app)
      (wrap-error-handling logger)
      (wrap-json-response)
      (wrap-defaults api-defaults)))



;; app component
;; ============================================================================
(defrecord WebServer [options web-app logger jetty]
  component/Lifecycle
  (start [component]
    (if (:jetty component)
      component
      (do
        (info logger [";; Starting jetty"])
        (assoc component
               :jetty (jetty/run-jetty (make-handler web-app logger)
                                       (assoc options :join? false))))))
  (stop [component]
    (let [jetty (:jetty component)]
      (if-not jetty
        component
        (do
          (info logger [";; Stopping jetty"])
          (.stop jetty)
          (assoc component :jetty nil))))))

(defn web-server [port & options]
  (map->WebServer {:options (into {:port port} options)}))
