(ns chatty.server
  (:require [clojure.java.io :as io]
            [clojure.core.async :refer [go-loop <!]]
            [chatty.dev :refer [is-dev? inject-devmode-html browser-repl start-figwheel]]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.route :refer [resources]]
            [net.cgrand.enlive-html :refer [deftemplate]]
            [net.cgrand.reload :refer [auto-reload]]
            [ring.middleware.reload :as reload]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [environ.core :refer [env]]
            [org.httpkit.server :refer [run-server]]
            [taoensso.sente :as s]
            [taoensso.encore :refer [uuid-str]]))

(let [{:keys [ch-recv send-fn ajax-post-fn
              ajax-get-or-ws-handshake-fn] :as sente-info}
      (s/make-channel-socket! {})]
  (def ws-post-handler ajax-post-fn)
  (def ws-get-handler ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn))

(defonce history (atom []))
(defonce sessions (atom []))

(deftemplate index-html (io/resource "index.html") []
  [:body] (if is-dev? inject-devmode-html identity))

(defn random-user-name []
  (str (rand-nth ["famous" "benevolent" "l33t" "ineffable" "dung" "abysmal"]) "_"
       (rand-nth ["monkey" "padawan" "hax0r" "dragon" "diver" "feminist"])))

(defn random-color []
  (rand-nth ["#282a2e" "#373b41" "#c5c8c6" "#969896" "#cc6666" "#de935f"
             "#f0c674" "#b5bd68" "#8abeb7" "#81a2be" "#b294bb"]))

(defn session-uid [req]
  (get-in req [:session :uid]))

(defn known-uid? [uid]
  (some #{uid} @sessions))

(defn create-new-session []
  (let [uuid (uuid-str)]
    (swap! sessions conj uuid)
    {:uid uuid
     :username (random-user-name)
     :color (random-color)}))

(defn index [req]
  {:status 200
   :session (if (known-uid? (session-uid req))
              (:session req)
              (merge (:session req) (create-new-session)))
   :headers {"Content-Type" "text/html"}
   :body (index-html)})

(defroutes routes
  (resources "/")
  (resources "/react" {:root "react"})
  (GET  "/ws" req (#'ws-get-handler req))
  (POST "/ws" req (#'ws-post-handler req))
  (GET  "/"   req (#'index req)))

(def http-handler
  (if is-dev?
    (reload/wrap-reload (wrap-defaults #'routes site-defaults))
    (wrap-defaults routes site-defaults)))

(defn run-web-server [& [port]]
  (let [port (Integer. (or port (env :port) 10555))]
    (print "Starting web server on port" port ".\n")
    (run-server http-handler {:port port :join? false})))

(defn run-auto-reload [& [port]]
  (auto-reload *ns*)
  (start-figwheel))

(defmulti handle-event (fn [[name _args] _req] name))

(defmethod handle-event :default
  [event req])

(defmethod handle-event :chatty/message [[_ message] req]
  (let [msg (assoc (:session req) :text message)]
    (swap! history conj msg)
    (doseq [uid @sessions]
      (println "- sending " msg uid)
      (chsk-send! uid [:chatty/message msg]))))

(defn run-event-loop []
  (go-loop [{:keys [client-uuid ring-req event]} (<! ch-chsk)]
    (future (handle-event event ring-req))
    (recur (<! ch-chsk))))

(defn run [& [port]]
  (when is-dev?
    (run-auto-reload))
  (run-web-server port)
  (run-event-loop))

(defn -main [& [port]]
  (run port))
