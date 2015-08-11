;; Copyright © 2015, JUXT LTD.

(ns yada.representations-test
  (:require
   [clojure.test :refer :all]
   [clojure.string :as str]
   [yada.charset :as charset]
   [yada.mime :as mime]
   [yada.util :refer (parse-csv)]
   [yada.negotiation :as negotiation]))

(defn best
  "Pick the best item from a collection. Since we are only interested in
  the best, we can avoid sorting the entire collection, which could be
  inefficient with large collections. The best element is selected by
  comparing items. An optional comparator can be given."
  ([coll]
   (best compare coll))
  ([^java.util.Comparator comp coll]
   (reduce
    (fn [x y]
      (case (. comp (compare x y)) (0 1) x -1 y))
    coll)))

(defn best-by
  "Pick the best item from a collection. Since we are only interested in
  the best, we can avoid sorting the entire collection, which could be
  inefficient with large collections. The best element is selected by
  applying the function given by keyfn to each item and comparing the
  result. An optional comparator can be given. The implementation uses a
  pair to keep hold of the result of applying the keyfn function, to
  avoid the redundancy of calling keyfn unnecessarily."
  ([keyfn coll]
   (best-by keyfn compare coll))
  ([keyfn ^java.util.Comparator comp coll]
   (first ;; of the pair
    (reduce (fn [x y]
              (if-not x
                ;; Our first pair
                [y (keyfn y)]
                ;; Otherwise compare
                (let [py (keyfn y)]
                  (case (. comp (compare (second x) py))
                    (0 1) x
                    -1 [y py]))))
            nil ;; seed
            coll))))

(deftest best-test
  (is (= (best [3 2 3 nil 19]) 19))
  (is (= (best (comp - compare) [3 2 3 nil 19]) nil)))

(deftest best-by-test
  (is (= (best-by first (comp - compare) [[3 9] [2 20] [3 -2] [nil 0] [19 10]]) [nil 0]))
  (is (= (best-by first (comp - compare) [[3 9] [2 20] [3 -2] [-2 0] [19 10]]) [-2 0])))

;; These are higher-order wrappers used by all dimensios of proactive
;; negotiation.

(defn- skip-rejected
  "Short-circuit attempts to process already rejected representation
  metadata."
  [f]
  (fn [rep]
    (if (:rejected rep) rep (f rep))))

(defn- wrap-quality-assessor
  "Return a function that will either reject, or associate a quality, to
  the given representation metadata."
  [f k]
  (fn [rep]
    (if-let [quality (f rep)]
      (assoc-in rep [:qualities k] quality)
      (assoc rep :rejected k))))

;; Content type negotation

(defn content-type-acceptable?
  "Compare a single acceptable mime-type (extracted from an Accept
  header) and a candidate. If the candidate is acceptable, return a
  sortable vector [acceptable-quality specificity parameter-count
  candidate-quality]. Specificity prefers text/html over text/* over
  */*. Parameter count gives preference to candidates with a greater
  number of parameters, which prefers text/html;level=1 over
  text/html. This meets the criteria in the HTTP
  specifications. Although the preference that should result with
  multiple parameters is not specified formally, candidates that have a
  greater number of parameters are preferred."
  ;; It is possible that these qualities could be coded into a long, since
  ;; "A sender of qvalue MUST NOT generate more than three digits after
  ;; the decimal point.  User configuration of these values ought to be
  ;; limited in the same fashion." -- RFC 7231 Section 5.3.1
  [rep acceptable]
  (when
      ;; TODO: Case sensitivity/insensitivity requirements
      (= (:parameters acceptable) (:parameters rep))
    (cond
      (and (= (:type acceptable) (:type rep))
           (= (:subtype acceptable) (:subtype rep)))
      [(:quality acceptable) 3 (count (:parameters rep)) (:quality rep)]

      (and (= (:type acceptable) (:type rep))
           (= (:subtype acceptable) "*"))
      [(:quality acceptable) 2 (count (:parameters rep)) (:quality rep)]

      (and (= (mime/media-type acceptable) "*/*"))
      [(:quality acceptable) 1 (count (:parameters rep)) (:quality rep)])))

(defn highest-content-type-quality
  "Given a collection of acceptable mime-types, return a function that will return the quality."
  [accepts]
  (fn [rep]
    (best (map (partial content-type-acceptable? (:content-type rep)) accepts))))

(defn make-content-type-quality-assessor
  [req k]
  (->
   (->> (get-in req [:headers "accept"]) parse-csv (map mime/string->media-type))
   highest-content-type-quality
   (wrap-quality-assessor :content-type)
   skip-rejected))

(defn- get-highest-content-type-quality
  "Given the request and a representation, get the highest possible
  quality value. Convenience function for independent testing. "
  [req rep]
  (let [k :content-type
        qa (make-content-type-quality-assessor req k)
        rep (qa rep)]
    (or (get-in rep [:qualities k])
        (when (:rejected rep) :rejected))))

(deftest content-type-test

  (testing "Basic match"
    (is (= (get-highest-content-type-quality
            {:headers {"accept" "text/html"}}
            {:content-type (mime/string->media-type "text/html")})
           [(float 1.0) 3 0 (float 1.0)])))

  (testing "Basic match, with multiple options"
    (is (= (get-highest-content-type-quality
            {:headers {"accept" "image/png,text/html"}}
            {:content-type (mime/string->media-type "text/html")})
           [(float 1.0) 3 0 (float 1.0)])))

  (testing "Basic match, with multiple options and q values"
      (is (= (get-highest-content-type-quality
              {:headers {"accept" "image/png,text/html;q=0.9"}}
              {:content-type (mime/string->media-type "text/html;q=0.8")})
             [(float 0.9) 3 0 (float 0.8)])))

  (testing "Basic reject"
    (is (= (get-highest-content-type-quality
            {:headers {"accept" "text/html"}}
            {:content-type (mime/string->media-type "text/plain")})
           :rejected)))

  (testing "Basic reject with multiple options"
    (is (= (get-highest-content-type-quality
            {:headers {"accept" "image/png,text/html"}}
            {:content-type (mime/string->media-type "text/plain")})
           :rejected)))

  (testing "Wildcard match"
    (is (= ((get-highest-content-type-quality
             {:headers {"accept" "image/png,text/*"}}
             {:content-type (mime/string->media-type "text/html")}) 1)
           ;; We get a match with a specificty score of 2
           2)))

  (testing "Specific match beats wildcard"
    (is (= ((get-highest-content-type-quality
             {:headers {"accept" "image/png,text/*,text/html"}}
             {:content-type (mime/string->media-type "text/html")}) 1)
           ;; We get a specificty score of 3, indicating we matched on the
           ;; text/html rather than the preceeding text/*
           3)))

  (testing "Specific match beats wildcard, different order"
    (is (= ((get-highest-content-type-quality
             {:headers {"accept" "text/html,text/*,image/png"}}
             {:content-type (mime/string->media-type "text/html")}) 1)
           3)))

  (testing "Parameter alignment"
    (is (= (get-highest-content-type-quality
            {:headers {"accept" "text/html;level=2"}}
            {:content-type (mime/string->media-type "text/html;level=1")})
           ;; We get a specificty score of 3, indicating we matched on the
           ;; text/html rather than the preceeding text/*
           :rejected)))

  (testing "Greater number of parameters matches"
    (is (= ((get-highest-content-type-quality
             {:headers {"accept" "text/html,text/html;level=1"}}
             {:content-type (mime/string->media-type "text/html;level=1")}) 2)
           ;; We get a specificty score of 3, indicating we matched on the
           ;; text/html rather than the preceeding text/*
           1))))

;; Charsets ------------------------------------

(defn charset-acceptable? [rep acceptable-charset]
  (when
      (or (= (charset/charset acceptable-charset) "*")
          (and
           (some? (charset/charset acceptable-charset))
           (= (charset/charset acceptable-charset)
              (charset/charset rep)))
          ;; Finally, let's see if their canonical names match
          (and
           (some? (charset/canonical-name acceptable-charset))
           (= (charset/canonical-name acceptable-charset)
              (charset/canonical-name rep))))
    [(:quality acceptable-charset) (:quality rep)]))

(defn highest-charset-quality
  "Given a collection of acceptable charsets, return a function that
  will return the quality."
  [accepts]
  (fn [rep]
    (best (map (partial charset-acceptable? (:charset rep)) accepts))))

(defn make-charset-quality-assessor
  [req k]
  (->
   (->> (get-in req [:headers "accept-charset"]) parse-csv (map charset/to-charset-map))
   highest-charset-quality
   (wrap-quality-assessor :charset)
   skip-rejected))

(defn- get-highest-charset-quality
  "Given the request and a representation, get the highest possible
  quality value. Convenience function for independent testing."
  [req rep]
  (let [k :charset
        qa (make-charset-quality-assessor req k)
        rep (qa rep)]
    (or (get-in rep [:qualities k])
        (when (:rejected rep) :rejected))))

(deftest charset-test

  (testing "Basic match"
    (is (= (get-highest-charset-quality
            {:headers {"accept-charset" "utf-8"}}
            {:charset (charset/to-charset-map "utf-8")})
           [(float 1.0) (float 1.0)])))

  (testing "Quality values"
    (is (= (get-highest-charset-quality
            {:headers {"accept-charset" "utf-8;q=0.8"}}
            {:charset (charset/to-charset-map "utf-8;q=0.9")})
           [(float 0.8) (float 0.9)])))

  (testing "Multiple choices"
    (is (= (get-highest-charset-quality
            {:headers {"accept-charset" "us-ascii,utf-8;q=0.9,Shift_JIS"}}
            {:charset (charset/to-charset-map "utf-8")})
           [(float 0.9) (float 1.0)])))

  (testing "Multiple choices but none ok"
    (is (= (get-highest-charset-quality
            {:headers {"accept-charset" "us-ascii,Shift_JIS"}}
            {:charset (charset/to-charset-map "utf-8")})
           :rejected))))

;; TODO: Test encodings - note that encodings can be combined

;; TODO: Test languages



;; "A request without any Accept header
;; field implies that the user agent
;; will accept any media type in response."
;; -- RFC 7231 Section 5.3.2

;; "A request without any Accept-Charset header field implies
;; that the user agent will accept any charset in response. "
;; -- RFC 7231 Section 5.3.3