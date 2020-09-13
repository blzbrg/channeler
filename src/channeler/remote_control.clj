(ns channeler.remote-control
  (:require [clojure.tools.logging :as log]
            [channeler.text-commands :as text-commands]))

(defn line-loop
  "Collect lines until a blank line, then return them. Blocks. They are returned in the order they
  were gotten. The final blank is not included."
  [line-reader]
  (loop [lines []]
    (let [line (.readLine line-reader)]
      (cond
        (nil? line) nil ;; EOF, discard incomplete command
        (clojure.string/blank? line) lines ;; command finished
        :else (recur (conj lines line))))))

(defn handle-command
  [context lines]
  (let [parsed (text-commands/parse-from-arglist lines)] ; pure
    (log/debug "Received command" (:arguments parsed))
    (text-commands/handle-command context parsed))) ; update state via STM


(defn command-loop
  [context line-reader]
  (if-let [lines (line-loop line-reader)] ; blocking
    ;; handle a command
    (do (handle-command context lines)
        (recur context line-reader))
    ;; conn closed
    nil))

(defn accept-loop
  [context listen-sock]
  (let [conn-sock (.accept listen-sock) ; blocking
        line-reader (->> (.getInputStream conn-sock)
                         (new java.io.InputStreamReader)
                         (new java.io.BufferedReader))]
    (log/debug "Connected to" (.getRemoteSocketAddress conn-sock))
    (command-loop context line-reader)
    (recur context listen-sock)))

(defn run-server
  [context port]
  (let [sock-addr (new java.net.InetSocketAddress "localhost" port)
        listen-sock (new java.net.ServerSocket)]
    (.bind listen-sock sock-addr) ; TODO: graceful error handling
    (log/info "Remote control server listening on" port)
    (accept-loop context listen-sock)))

(defn init
  [{state :state :as context}]
  (future (run-server context 1337))
  ;; TODO: save enough to terminate it later
  state)
