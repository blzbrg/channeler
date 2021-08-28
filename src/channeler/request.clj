(ns channeler.request)

;; === Path utils ===

(defn req-path
  [req]
  (concat (::path-in-context req) [::requests (::req-key req)]))

(defn resp-path
  [resp]
  (concat (::path-in-context resp) [::responses (::req-key resp)]))

;; === Find and contexify ===

(defn contexify-req-impl
  [req path-in-context req-key]
  ;; always convert to seq for consistency
  (assoc req ::path-in-context (seq path-in-context) ::req-key req-key))

(defn contexified-req
  "Grab a request out of the context and add enough information for the response to be integrated in
  the right place. Once a req is contextified it is suitable for use with other functions taking
  reqs and contexts.

  `path-in-context` is a path to a map which contains a `::requests` map. Thus a path `[:a :b]` and
  a key `:c` means that a request is expected in `[:a :b ::requests :c]`."
  [ctx path-in-context req-key]
  (let [req (get-in (get-in ctx path-in-context) [::requests req-key])]
    (contexify-req-impl req path-in-context req-key)))

;; === Removing requests ===

(defn maybe-cleanup-reqs
  [req-container]
  (if (empty? (::requests req-container))
    (dissoc req-container ::requests)
    req-container))

(defn remove-req-from-context
  [ctx {key ::req-key path-in-context ::path-in-context :as req}]
  ;; remove req itself
  (let [req-map-path (concat path-in-context [::requests])
        new-ctx (update-in ctx req-map-path dissoc key)]
    ;; potentially remove the empty request map
    (update-in new-ctx path-in-context maybe-cleanup-reqs)))

;; TODO: Optimize for common case of removing every request
(defn remove-requests-from-context
  [ctx reqs]
  (reduce remove-req-from-context ctx reqs))

;; === Request/response API ===
(defn add-request [m request-key request]
  (update m ::requests merge {request-key request}))

(defn has-responses? [m]
  (not-empty (::requests m)))

(defn integrate-response
  [ctx {key ::req-key :as resp}]
  (assoc-in ctx (resp-path resp) resp))

(defn integrate-responses
  [ctx resps]
  (reduce integrate-response ctx resps))
