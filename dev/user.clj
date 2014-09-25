(ns user
  (:require [clojure.pprint :refer (pprint pp)]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [cmr.dev-system.system :as system]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.dev.util :as d]
            [cmr.system-int-test.data2.core :as data]
            [cmr.common.config :as config]
            [earth.driver :as earth-viz]
            [common-viz.util :as common-viz]
            [vdd-core.core :as vdd])
  (:use [clojure.test :only [run-all-tests]]
        [clojure.repl]
        [alex-and-georges.debug-repl]
        [cmr.common.dev.capture-reveal]))

(def system nil)

(def system-type #_:external-dbs :in-memory)


(defn start
  "Starts the current development system."
  []
  (config/reset-config-values)
  (data/reset-uniques)

  ;; Set the default job start delay to avoid jobs kicking off with tests etc.
  (config/set-config-value! :default-job-start-delay (str (* 3 3600)))

  ; ;; Temporary to enable use of local ECHO
  ; (config/set-config-value! :echo-rest-port 10000)
  ; (config/set-config-value! :echo-rest-context "/echo-rest")
  ; (config/set-config-value! :echo-system-token "E09BD9529FB25FE2E040007F01003E55")

    (let [s (system/create-system system-type)]
    (alter-var-root #'system
                    (constantly
                      (system/start s))))
  (d/touch-user-clj))

(defn stop
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system
                  (fn [s]
                    (when s (system/stop s)))))

(defn reset []
  ; Stops the running code
  (stop)
  ; Refreshes all of the code and then restarts the system
  (refresh :after 'user/start))


(defn reload-coffeescript []
  (do
    (println "Compiling coffeescript")
    (println (common-viz/compile-coffeescript (get-in system [:components :vdd-server :config])))
    (vdd/data->viz {:cmd :reload})))

(info "Custom dev-system user.clj loaded.")
