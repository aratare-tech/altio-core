(defproject altio "0.1.0-SNAPSHOT"

  :description "MDSD code generator"
  :url "http://github.com/aratare-tech/altio-core"

  :dependencies [[mount "0.1.16"]
                 [cprop "0.1.16"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.cli "1.0.194"]
                 [org.clojure/tools.logging "1.0.0"]
                 [org.antlr/antlr4-runtime "4.9"]]

  :min-lein-version "2.0.0"

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/clj"]
  :resource-paths ["test/resources"]

  :profiles {:uberjar {:omit-source   true
                       :prep-tasks    ["compile"]
                       :aot           :all
                       :uberjar-name  "altio.jar"
                       :target-path   "target/%s/"
                       :main          ^:skip-aot altio.core
                       :clean-targets ^{:protect false} [:target-path]}

             :dev     {:plugins      [[lein-cloverage "1.2.1"]]
                       :dependencies [[pjstadig/humane-test-output "0.10.0"]]
                       :injections   [(require 'pjstadig.humane-test-output)
                                      (pjstadig.humane-test-output/activate!)]}})