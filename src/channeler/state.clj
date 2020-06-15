(ns channeler.state)

(def static-defaults
  {
   :post-transcade {:transformers []}
   })

(defn initial-state
  "Take options and produce initial state. This is the state before plugins are loaded"
  [options]
  (merge static-defaults
         {::dir (or (get options :channeler.options/dir) (System/getProperty "user.dir"))
          }))