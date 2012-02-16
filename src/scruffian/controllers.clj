(ns scruffian.controllers
  (:use [clj-jargon.jargon]
        [scruffian.error-codes])
  (:require [scruffian.actions :as actions]
            [clojure-commons.file-utils :as ft]
            [scruffian.ssl :as ssl]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [ring.util.response :as rsp-utils]))

(def props (atom nil))

(defn ctlr-init
  [prps]
  (reset! props prps))

(defn is-failed?
  "Checks the map 'result-msg' to see if it represents
   a failed jargon-core call."
  [result-msg]
  (log/debug (str "is-failed? " result-msg))
  (= "failure" (:status result-msg)))

(defn create-response
  "Creates a Ring-compatible response map from the 'results' map returned
   by the calls into irods."
  ([results] (create-response results "text/plain"))
  ([results content-type]
    (log/debug (str "create-response " results))
    (let [status (if (not (is-failed? results)) 200 400)
          body (json/json-str results)
          retval (merge
                   (rsp-utils/content-type (rsp-utils/response "") content-type)
                   {:status status :body body})]
      (log/info (str "Returning " (json/json-str retval)))
      retval)))

(defn invalid-fields
  "Validates the format of a map against a spec.

   map-spec is a map where the key is the name of a
   corresponding field in a-map that must exist. The
   value is a function that returns true or false
   when the corresponding value in a-map is passed into
   it.

   Returns a sequence of field-names from 'a-map'that
   aren't compliant with the spec. They're either missing
   or the validator function returned false when the
   value was passed in."
  [a-map map-spec]
  (log/debug (str "invalid-fields " a-map " " map-spec))
  (filter (comp not nil?)
          (for [[field-name validator?] map-spec]
            (if (contains? a-map field-name)
              (if (validator? (get a-map field-name)) nil field-name)
              field-name))))

(defn map-is-valid?
  "Returns true if the 'a-map' conforms to 'map-spec'."
  [a-map map-spec]
  (log/debug (str "map-is-valid? " a-map " " map-spec))
  (if (map? a-map)
    (== 0 (count (invalid-fields a-map map-spec)))
    false))

(defn valid-body?
  [request body-spec]
  (cond
    (not (map? (:body request)))
    false
    
    (not (map-is-valid? (:body request) body-spec))
    false
    
    :else
    true))

(defn query-param
  "Grabs the 'field' from the query string associated
   with the request and returns it.

   Parameters:
      request - request map put together by Ring.
      field - name of the query value to return.
   "
  [request field]
  (log/debug (str "query-param " field))
  (get (:query-params request) field))

(defn query-param?
  "Checks to see if the specified query-param actually exists
   in the request.

   Parameters:
      request - request map put together by Ring.
      field - name of the query key to check for."
  [request field]
  (log/debug (str "query-param?" field))
  (contains? (:query-params request) field))

(defn form-param
  "Grabs the 'field' from the form-data associated with
   the request and returns it.

   Parameters:
     request - request map put together by Ring.
     field - name of the form-data value to return."
  [request field]
  (log/debug (str "form-param " field))
  (get (:params request) field))

(defn bad-query [key action]
  (create-response
    {:action action
     :status "failure"
     :error_code ERR_MISSING_QUERY_PARAMETER}))

(defn bad-body 
  [request body-spec]
  (cond
    (not (map? (:body request)))
    {:status "failure"
     :action "body-check"
     :error_code ERR_INVALID_JSON}
    
    (not (map-is-valid? (:body request) body-spec))
    {:status "failure"
     :error_code ERR_BAD_OR_MISSING_FIELD
     :fields (invalid-fields  (:body request) body-spec)}
    
    :else
    {:status "success"}))

(defn gen-uuid []
  (str (java.util.UUID/randomUUID)))

(defn store
  [istream filename user dest-dir]
  (actions/store istream user (ft/path-join dest-dir filename)))

(defn store-irods
  [{stream :stream orig-filename :filename}]
  (let [uuid     (gen-uuid)
        filename (str orig-filename "." uuid)
        user     (get @props "scruffian.irods.username")
        home     (get @props "scruffian.irods.home")
        temp-dir (get @props "scruffian.irods.temp-dir")]
    (log/warn filename)
    (with-jargon
      (store stream filename user temp-dir))))

(defn do-download
  [request]
  (cond
    (not (query-param? request "user")) 
    (bad-query "user" "download")
    
    (not (query-param? request "path"))
    (bad-query "path" "download")
    
    :else
    (let [user     (query-param request "user")
          filepath (query-param request "path")
          resp     (actions/download user filepath)]
      (log/debug "in do-download.")
      (if (= (:status resp) 200)
        resp
        (create-response resp)))))

(defn do-upload
  [request]
  (log/warn (str "REQUEST: " request))
  (let [user     (form-param request "user")
        dest     (form-param request "dest")
        up-path  (get (:multipart-params request) "file")]
    (create-response (actions/upload user up-path dest))))

(defn do-urlupload
  [request]
  (cond
    (not (query-param? request "user"))
    (bad-query "user" "url-upload")
    
    (not (valid-body? request {:dest string? :address string?}))
    (create-response (bad-body request {:dest string? :address string?}))
    
    :else
    (let [user    (query-param request "user")
          dest    (:dest (:body request))
          fname   (ft/basename dest)
          ddir    (ft/dirname dest)
          addr    (:address (:body request))
          istream (ssl/input-stream addr)]
      (log/warn (str "User: " user))
      (log/warn (str "Dest: " dest))
      (log/warn (str "Fname: " fname))
      (log/warn (str "Ddir: " ddir))
      (log/warn (str "Addr: " addr))  
      (create-response (actions/urlimport user addr fname ddir)))))

(defn do-saveas
  [request]
  (log/warn (str "REQUEST: " request))
  (cond
      (not (query-param? request "user"))
      (bad-query "user" "saveas")
      
      (not (valid-body? request {:dest string? :content string?}))
      (create-response (bad-body request {:dest string? :content string?}))
      
      :else
      (let [user (query-param request "user")
            dest (:dest (:body request))
            cont (:content (:body request))]
        (with-jargon
          (cond
            (not (user-exists? user))
            (create-response
              {:action "saveas"
               :status "failure"
               :error_code ERR_NOT_A_USER})
            
            (not (exists? (ft/dirname dest)))
            (create-response 
              {:action "saveas"
               :status "failure"
               :error_code ERR_DOES_NOT_EXIST
               :path (ft/dirname dest)})
            
            (not (is-writeable? user (ft/dirname dest)))
            (create-response
              {:action "saveas"
               :status "failure"
               :error_code ERR_NOT_WRITEABLE
               :path (ft/dirname dest)})
            
            (exists? dest)
            (create-response
              {:action "saveas"
               :status "failure"
               :error_code ERR_EXISTS
               :path dest})
            
            :else
            (with-in-str cont
              (actions/store *in* user dest)))))))
