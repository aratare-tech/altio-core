(ns epsilon.generator
  (:require [puget.printer :refer [pprint]]
            [me.raynes.fs :as fs]
            [taoensso.timbre :as log]
            [epsilon.utility :refer :all]
            [medley.core :as m])
  (:import [org.eclipse.epsilon.egl EgxModule]
           [org.eclipse.epsilon.emc.plainxml PlainXmlModel]
           [epsilon CustomEglFileGeneratingTemplateFactory DirectoryWatchingUtility]
           [org.eclipse.epsilon.evl EvlModule]))

(defn path->xml
  "Load the model at the path and convert it to PlainXmlModel."
  [path]
  (doto (new PlainXmlModel)
    (.setFile (fs/file path))
    (.setName (fs/name path))
    (.load)))

(defn ->template-factory
  "Return a new template factory that outputs to the given path."
  [path]
  (new CustomEglFileGeneratingTemplateFactory path))


(defmulti ->epsilon-module
          "Given a path, convert it into the appropriate module based on its extension."
          (fn [path & _] (fs/extension path)))

(defmethod ->epsilon-module ".egx"
  [path output-path]
  (let [factory        (->template-factory output-path)
        egx-file       (fs/file path)
        egx-module     (doto (new EgxModule factory) (.parse egx-file))
        parse-problems (.getParseProblems egx-module)]
    (if (empty? parse-problems)
      egx-module
      (throw (ex-info "Parsed problems found" {:payload parse-problems})))))

(defmethod ->epsilon-module ".evl"
  [path & _]
  (let [evl-file       (fs/file path)
        evl-module     (doto (new EvlModule) (.parse evl-file))
        parse-problems (.getParseProblems evl-module)]
    (if (empty? parse-problems)
      evl-module
      (throw (ex-info "Parsed problems found" {:payload parse-problems})))))

(defn path->epsilon-files
  "Return all files that satisfy any of the given types within the given directory.

  Accept eol?, egl?, egx? and evl? or any combinations. Ignore invalid functions."
  ([path types]
   (path->epsilon-files path types true))
  ([path types recursive?]
   (let [types     (filter #{eol? egl? egx? evl?} types)
         filter-fn #(->> % ((apply juxt types)) ((partial some true?)))]
     (if recursive?
       (m/join (fs/walk (fn [root _ files] (->> files (filter filter-fn) (map #(fs/file root %)))) path))
       (->> path (fs/list-dir) (filter fs/file?) (filter filter-fn))))))

(defn execute
  "Execute an EOL file with the given XML models."
  ([model-paths path & args]
   (let [xml-models (doall (map #(path->xml %) model-paths))]
     (doto (apply ->epsilon-module path args)
       (-> .getContext .getModelRepository .getModels (.addAll xml-models))
       (.execute)))))

(defn generate
  "Generate an EGX file with the given XML models."
  [egx-path model-paths output-path]
  (execute model-paths egx-path output-path))

(defn validate
  "Validate an EVL file with the given XML models."
  [evl-path model-paths]
  (let [module      (execute model-paths evl-path)
        constraints (-> module .getContext .getUnsatisfiedConstraints)]
    (if (empty? constraints)
      module
      (throw (ex-info "Constraints violation were found." {:payload constraints})))))

(defmulti file-change-handler
          "Triggered when a file change. Will dispatch according to what type of file just got changed."
          (fn [file _ _] (fs/extension file)))

(defmethod file-change-handler ".egx"
  [file model-paths output-dir-path]
  (log/info file "changed. Regenerating.")
  (handle-exception #(generate file model-paths output-dir-path)))

(defmethod file-change-handler ".egl"
  [egl-file model-paths output-dir-path]
  (log/info egl-file "changed. Regenerating using the accompanying EGX.")
  (let [egx-path (replace-ext egl-file "egx")]
    (if (fs/exists? egx-path)
      (handle-exception #(generate egx-path model-paths output-dir-path))
      (log/error "Unable to hot-reload because accompanying" egx-path "file is missing."))))

(defmethod file-change-handler ".evl"
  [file model-paths _]
  (log/info file "changed. Rerun validation.")
  (handle-exception #(validate (.getAbsolutePath file) model-paths)))

(defmethod file-change-handler ".eol"
  [file _ _]
  (log/info file "changed. Regenerating")
  ;; Nothing yet when EOL is created/modified. One thing we can do is to figure out
  ;; which modules depend on this EOL, then trigger a hot reload on the leaf modules
  ;; since doing so will trigger a down-cascade hot-reload on all relevant dependent
  ;; modules. But meh maybe later.
  (log/warn "No EOL support yet."))

(defn watch
  "Watch the given template directory and regenerate if file change is detected.

  Takes a bunch of preds that will filter out which file type to listen to.

  For example, (watch _ _ _ egl?) will listen for EGL files only. Can add more as see fit."
  ([template-dir model-paths output-path]
   (watch template-dir model-paths output-path [egl? egx? evl? eol?]))
  ([template-dir model-paths output-path preds]
   (let [watcher (DirectoryWatchingUtility/watch (-> template-dir fs/file .toPath)
                                                 (fn [f] (true? (some true? ((apply juxt preds) f))))
                                                 (fn [f] (file-change-handler f model-paths output-path))
                                                 (fn [f] (file-change-handler f model-paths output-path))
                                                 (fn [f] (file-change-handler f model-paths output-path)))]
     {:future  (.watchAsync watcher)
      :handler (fn [] (.close watcher))})))

(defn validate-all
  "Go through the provided template directory and validate everything.

  If watch? is true, return the watcher handler to be called to stop the watcher."
  ([{:keys [template-dir model-paths watch?]}]
   (validate-all template-dir model-paths watch?))
  ([template-dir model-paths]
   (validate-all template-dir model-paths true))
  ([template-dir model-paths watch?]
   (let [evl-files   (path->epsilon-files template-dir [evl?])
         evl-modules (doall (map #(validate % model-paths) evl-files))]
     (if watch?
       (watch template-dir model-paths nil [evl?])
       evl-modules))))

(defn generate-all
  "Go through the provided template directory and generate everything.

  If watch? is true, return the watcher handler to be called to stop the watcher."
  ([{:keys [template-dir model-paths output-dir-path watch?]}]
   (generate-all template-dir model-paths output-dir-path watch?))
  ([template-dir model-paths output-path]
   (generate-all template-dir model-paths output-path true))
  ([template-dir model-paths output-path watch?]
   (let [egx-files   (path->epsilon-files template-dir [egx?])
         egx-modules (doall (map #(generate % model-paths output-path) egx-files))]
     (if watch?
       (watch template-dir model-paths output-path [egx? egl?])
       egx-modules))))
