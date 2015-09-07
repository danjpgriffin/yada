(ns yada.console.handlers
  (:require [re-frame.core :as re-frame]
            [thi.ng.tweeny.core :as tweeny]
            [yada.console.db :as db]))

(re-frame/register-handler
 :initialize-db
 (fn  [_ _]
   db/default-db))

(re-frame/register-handler
 :set-active-panel
 (fn [db [_ active-panel id]]
   (case active-panel
     :home-panel
     (assoc db :active-panel active-panel)
     :request-panel
     (->
      db
      (assoc :active-panel active-panel)
      (assoc :active-request (get (:requests db) id))))))

(defn animate [n frame-count]
  (when (< n frame-count)
    (re-frame/dispatch [:animation-frame (/ (float n) frame-count)])
    (.requestAnimationFrame js/window #(animate (inc n) frame-count))))

(re-frame/register-handler
 :animation-frame
 (fn [db [_ x]]
   (assoc-in db [:animation :current-value]
             (- (get-in db [:animation :from])
                (int (* x (get-in db [:animation :from])))))))

(re-frame/register-handler
 :animate-card
 (fn [db [_ {:keys [a b dur]}]]
   (animate 1 12)
   db))

(re-frame/register-handler
 :card-click
 (fn [db [_ card-id]]
   (let [node (get-in db [:cards card-id :node])]
     (println "making card active")
     (assoc db
            :active-card {:id card-id
                          :dimensions (.. node -parentElement -parentElement getBoundingClientRect)}))))

(re-frame/register-handler
 :card-did-mount
 (fn [db [_ id node]]
   (println "Card did mount:" id (.getBoundingClientRect node))
   (assoc-in db [:cards id :node] node)))

(re-frame/register-handler
 :close-card
 (fn [db _]
   (println "closing card")
   (assoc db :active-card {:id :none})))
