(ns channeler.remote-control
  (:require [channeler.config :refer [conf-get]]
            [clojure.tools.logging :as log]
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
    (log/debug "Received command" parsed)
    (text-commands/handle-command context parsed))) ; update state via STM


(defn command-loop
  [context conn-sock line-reader]
  (if-let [lines (line-loop line-reader)] ; blocking
    ;; handle a command
    (do (handle-command context lines)
        (recur context conn-sock line-reader))
    ;; conn closed by other end (ie. we received a FIN), we should explicitly close the socket. This
    ;; is needed for when the other end is openbsd-netcat. When openbsd-netcat is run as nc -N, it
    ;; will send a FIN when getting EOF from stdin, but will not exit until it gets a FIN from the
    ;; other side. closing the socket explicitly sends the FIN. socat, gnu-netcat, and ncat all do
    ;; not care about receiving the FIN
    ;; See https://serverfault.com/questions/783169/bsd-nc-netcat-does-not-terminate-on-eof
    (do (log/debug "Connection closed by other end")
        (.close conn-sock))))

(defn accept-loop
  [context listen-sock]
  (let [conn-sock (.accept listen-sock) ; blocking
        line-reader (->> (.getInputStream conn-sock)
                         (new java.io.InputStreamReader)
                         (new java.io.BufferedReader))]
    (log/debug "Connected to" (.getRemoteSocketAddress conn-sock))
    (command-loop context conn-sock line-reader)
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
  (future (run-server context (conf-get (:conf context) "remote-control" "port")))
  ;; TODO: save enough to terminate it later
  state)
