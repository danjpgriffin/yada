(ns yada.protocols)

(defprotocol Callbacks
  (service-available? [_] "Return whether the service is available")
  (known-method? [_ method])
  (request-uri-too-long? [_ uri])
  (allowed-method? [_ method swagger-ops])
  (find-resource [_ opts])
  (model [_ opts])
  (body [_ opts]))

(extend-protocol Callbacks
  Boolean
  (service-available? [b] b)
  (request-uri-too-long? [b _] b)
  (allowed-method? [b _ _] b)
  (find-resource [b opts] (when b {}))

  clojure.lang.IFn
  (service-available? [f] (f))
  (known-method? [f method] (f method))
  (request-uri-too-long? [f uri] (f uri))
  (allowed-method? [f method op] (f method op))
  (find-resource [f opts] (f opts))
  (model [f opts] (f opts))
  (body [f opts] (f opts))

  String
  (body [s opts] s)

  Number
  (request-uri-too-long? [n uri]
    (> (.length uri) n))

  clojure.lang.PersistentHashSet
  (known-method? [set method]
    (contains? set method))
  (allowed-method? [set method op]
    (contains? set method))

  clojure.lang.PersistentArrayMap
  (model [m opts] m)

  nil
  (find-resource [_ opts] nil))