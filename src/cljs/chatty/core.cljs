(ns chatty.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [taoensso.sente :as s]))

(enable-console-print!)

(defonce app-state (atom {:history []}))

(defonce websocket
  (let [{:keys [chsk ch-recv send-fn state] :as sente-info}
        (s/make-channel-socket! "/ws" {:type :auto})]
    (def chsk chsk)
    (def ch-chsk ch-recv)
    (def chsk-send! send-fn)
    (def chsk-state chsk-state)
    sente-info))

(defmulti handle-event (fn [[ev-id _] _ _] ev-id))

(defmethod handle-event :chatty/message [[_ msg] app owner]
  (println ">>" msg)
  (swap! app-state #(update-in % [:history] conj msg)))

(defn event-loop [app owner]
  (go-loop [[op arg] (:event (<! ch-chsk))]
    (when (= op :chsk/recv)
      (println "handling" arg)
      (handle-event arg app owner))
    (recur (:event (<! ch-chsk)))))

(defn field-change
  [e owner field]
  (let [value (.. e -target -value)]
    (om/set-state! owner field value)))

(defn send-text-on-enter [e owner state]
  (let [kc (.-keyCode e)
        w (.-which e)]
    (when (or (== kc 13) (== w 13))
      (chsk-send! [:chatty/message (:text state)])
      (om/set-state! owner :text ""))))

(defn application [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:text ""})
    om/IWillMount
    (will-mount [_]
      (event-loop app owner))
    om/IRenderState
    (render-state [_ state]
      (html [:div
             [:div {:class "messages"}
              (map (fn [msg]
                     [:div {:class "message"
                            :style {:color (:color msg)}
                            } (str "<" (:username msg) "> "(:text msg))]) (:history app))]
             [:input {:type "text"
                      :class "message-input"
                      :value (:text state)
                      :on-change #(field-change % owner :text)
                      :on-key-press #(send-text-on-enter % owner state)}]]))))

(defn main []
  (om/root application
           app-state
           {:target (. js/document (getElementById "app"))}))
