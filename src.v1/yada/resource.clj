;; Copyright © 2015, JUXT LTD.

(ns yada.resource
  (:require [yada.interceptors :as i]
            [yada.security :as sec]
            [yada.util :refer [arity]])
  (:import java.util.Date
           yada.charset.CharsetMap
           yada.media_type.MediaTypeMap))

(defprotocol ResourceCoercion
  (as-resource [_] "Coerce to a resource. Often, resources need to be
  coerced rather than extending types directly with the Resource
  protocol. We can exploit the time of coercion to know the time of
  birth for the resource, which supports time-based conditional
  requests. For example, a simple StringResource is immutable, so by
  knowing the time of construction, we can precisely state its
  Last-Modified-Date."))

;; --

(def default-interceptor-chain
  [i/available?
   i/known-method?
   i/uri-too-long?
   i/TRACE
   i/method-allowed?
   ;;i/parse-parameters
   i/capture-proxy-headers
   sec/authenticate
   i/get-properties
   sec/authorize
   i/process-request-body
   i/check-modification-time
   i/select-representation
   ;; if-match and if-none-match computes the etag of the selected
   ;; representations, so needs to be run after select-representation
   ;; - TODO: Specify dependencies as metadata so we can validate any
   ;; given interceptor chain
   i/if-match
   i/if-none-match
   i/invoke-method
   i/get-new-properties
   i/compute-etag
   sec/access-control-headers
   sec/security-headers
   i/create-response
   i/logging
   i/return
   ])

(def default-error-interceptor-chain
  [sec/access-control-headers
   i/create-response
   i/logging
   i/return])

;; --

(defrecord Resource []
  ResourceCoercion
  (as-resource [this] this))

(defn resource [model]
  (let [r (merge ;; TODO conform
           {:interceptor-chain default-interceptor-chain
            :error-interceptor-chain default-error-interceptor-chain}
           model)]
    #_(when (su/error? r) (throw (ex-info "Cannot turn resource-model into resource, because it doesn't conform to a resource-model schema" {:resource-model model :error (:error r)})))
    (map->Resource r)))

(extend-protocol ResourceCoercion
  nil
  (as-resource [_]
    (resource
     {:summary "Nil resource"
      :methods {}
      :interceptor-chain [(fn [_]
                            (throw (ex-info "" {:status 404})))]}))
  clojure.lang.Fn
  (as-resource [f]
    (let [ar (arity f)]
      (resource
       {:produces #{"text/html"
                    "text/plain"
                    "application/json"
                    "application/edn"}
        :methods
        {:* {:response (fn [ctx]
                         (case ar
                           0 (f)
                           1 (f ctx)
                           (apply f ctx (repeat (dec arity) nil)))
                         )}}}))))

;; Convenience functions

(defn prepend-interceptor [res & interceptors]
  (update res
          :interceptor-chain (partial into (vec interceptors))))

(defn insert-interceptor [res point & interceptors]
  (update res :interceptor-chain
          (partial mapcat (fn [i]
                            (if (= i point)
                              (concat interceptors [i])
                              [i])))))

(defn append-interceptor [res point & interceptors]
  (update res :interceptor-chain
          (partial mapcat (fn [i]
                            (if (= i point)
                              (concat [i] interceptors)
                              [i])))))