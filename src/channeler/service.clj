(ns channeler.service
  (:require [channeler.request :as request]
            [clojure.tools.logging :as log]))

;; === Proto ===

(defprotocol Service
  (handle-item! [this item] "Dispatch this item to this service.")
  (stop [this] "Stop this service. Blocks until stopped."))

;; === Core dispatch logic ===

;;    item{service-key} ---+
;;            |            |
;;            |            +------------------------------+
;;            |                                           |
;;            +---> [service-map]----> (service) <--------+
;;
;; Request service key determines which service is selected from service-map.

(defn dispatch!
  "Given a service-map, a request-key, and an item, dispatch the item to the appropriate service."
  [service-map item]
  (let [service-key (::service-key item)
        service (get service-map service-key)]
    (handle-item! service item)))

;; === Dispatching request API ===

(defn dispatch-requests-from-context!
  [service-map ctx reqs]
  (dorun (map (partial dispatch! service-map) reqs))
  (request/remove-requests-from-context ctx reqs))
