(defproject channeler "0.1.0-SNAPSHOT"
  :description "A flexible chan downloader"
  :url "https://github.com/blzbrg/channeler"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/data.json "1.0.0"]
                 [org.clojure/tools.cli "1.0.194"]]
  :main ^:skip-aot channeler.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
