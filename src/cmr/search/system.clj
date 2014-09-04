(ns cmr.search.system
  (:require [cmr.common.lifecycle :as lifecycle]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.api.web-server :as web]
            [cmr.common.cache :as cache]
            [clojure.core.cache :as clj-cache]
            [cmr.acl.acl-cache :as ac]
            [cmr.common.jobs :as jobs]
            [cmr.search.api.routes :as routes]
            [cmr.search.data.elastic-search-index :as idx]
            [cmr.system-trace.context :as context]
            [cmr.metadata-db.system :as mdb-system]
            [cmr.common.config :as cfg]
            [cmr.transmit.config :as transmit-config]
            [cmr.elastic-utils.config :as es-config]
            [cmr.search.services.query-execution.has-granules-results-feature :as hgrf]
            [cmr.search.services.acls.acl-cache-extension :as ace]))

;; Design based on http://stuartsierra.com/2013/09/15/lifecycle-composition and related posts

(def TOKEN_CACHE_TIME
  "The number of milliseconds token information will be cached for."
  (* 5 60 1000))

(def search-public-protocol (cfg/config-value :search-public-protocol "http"))
(def search-public-host (cfg/config-value :search-public-host "localhost"))
(def search-public-port (cfg/config-value :search-public-port 3003 transmit-config/parse-port))
(def search-relative-root-url (cfg/config-value :search-relative-root-url ""))

(def search-public-conf
  {:protocol search-public-protocol
   :host search-public-host
   :port search-public-port
   :relative-root-url search-relative-root-url})

(def
  ^{:doc "Defines the order to start the components."
    :private true}
  component-order [:log :search-index :web :scheduler])

(def system-holder
  "Required for jobs"
  (atom nil))

(defn create-system
  "Returns a new instance of the whole application."
  []
  (let [metadata-db (-> (mdb-system/create-system "metadata-db-in-search-app-pool")
                        (dissoc :log :web :scheduler))
        sys {:log (log/create-logger)

             ;; An embedded version of the metadata db app to allow quick retrieval of data
             ;; from oracle.
             :metadata-db metadata-db
             :search-index (idx/create-elastic-search-index (es-config/elastic-config))
             :web (web/create-web-server (transmit-config/search-port) routes/make-api)
             :caches {:index-names (cache/create-cache)
                      :acls (ac/create-acl-cache)
                      ;; Caches a map of tokens to the security identifiers
                      :token-sid (cache/create-cache (clj-cache/ttl-cache-factory {} :ttl TOKEN_CACHE_TIME))
                      :has-granules-map (hgrf/create-has-granules-map-cache)}
             :acl-cache-extension-fns [ace/handle-acl-cache-update]
             :zipkin (context/zipkin-config "Search" false)
             :search-public-conf search-public-conf
             :scheduler (jobs/create-scheduler
                          `system-holder
                          [(ac/refresh-acl-cache-job "search-acl-cache-refresh")
                           hgrf/refresh-has-granules-map-job])}]
    (transmit-config/system-with-connections sys [:index-set :echo-rest])))

(defn start
  "Performs side effects to initialize the system, acquire resources,
  and start it running. Returns an updated instance of the system."
  [this]
  (info "System starting")
  (let [started-system (reduce (fn [system component-name]
                                 (update-in system [component-name]
                                            #(lifecycle/start % system)))
                               (update-in this [:metadata-db] mdb-system/start)
                               component-order)]
    (info "System started")
    started-system))


(defn stop
  "Performs side effects to shut down the system and release its
  resources. Returns an updated instance of the system."
  [this]
  (info "System shutting down")
  (let [stopped-system (reduce (fn [system component-name]
                                 (update-in system [component-name]
                                            #(lifecycle/stop % system)))
                               this
                               (reverse component-order))
        stopped-system (update-in stopped-system [:metadata-db] mdb-system/stop)]
    (info "System stopped")
    stopped-system))
