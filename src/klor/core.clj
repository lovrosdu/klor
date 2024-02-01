(ns klor.core
  (:require [clojure.set :refer [union]]
            [klor.util :refer [unmetaify] :as u]
            [klor.roles :refer [role-expand role-analyze ]]
            [puget.printer :as puget]))

;;; Printing

(defrecord Colored [color value])

(def fg-colors
  ;; Taken from `puget.color.ansi/sgr-code`.
  [:cyan :yellow :red :magenta :blue :green :white :black])

(def bg-colors
  ;; Taken from `puget.color.ansi/sgr-code`.
  [:bg-cyan :bg-yellow :bg-red :bg-magenta
   :bg-blue :bg-green :bg-white :bg-black])

(defn make-color-scheme [color]
  ;; Taken from `puget.printer/*options*`.
  {:delimiter [:bold color]
   :tag [:bold color]
   :nil [:bold color]
   :boolean [:bold color]
   :number [:bold color]
   :string [:bold color]
   :character [:bold color]
   :keyword [:bold color]
   :symbol [:bold color]
   :function-symbol [:bold color]
   :class-delimiter [:bold color]
   :class-name [:bold color]})

(defn colored-handler [opts colored]
  (puget/cprint-str
   (:value colored)
   (merge opts {:color-scheme (make-color-scheme (:color colored))})))

(defn color-chor-form [roles form]
  (u/postwalk #(let [{:keys [role]} (meta %)]
                 (->Colored (or (roles role) :white) (unmetaify %)))
              form))

(defn print-chor-form
  "Print a role-analyzed FORM with syntax coloring, using one color for per role.

  COLORS maps each role to a color. By default, colors are taken from
  `fg-colors`."
  ([form]
   (print-chor-form form (zipmap (:roles (meta form)) fg-colors)))
  ([form colors]
   (-> (color-chor-form colors form)
       (puget/cprint {:print-handlers {klor.core.Colored colored-handler}
                      :width 500}))))

(defn role-visualize [roles form & colors]
  (let [analyzed (role-analyze roles (role-expand roles form))]
    (puget/pprint form {:width 500})
    (print "  => ")
    (apply print-chor-form analyzed colors)))

(defn -main []
  (role-visualize '#{Ana Bob}
                  '(let [Ana/x Bob/x] (if Ana/x Bob/x Bob/x)))
  (role-visualize '#{Ana Bob Cal Dan}
                  '(let [(Bob x) (Ana x)]
                     (let [(Cal x) (Bob x)]
                       (let [(Dan x) (Cal x)]
                         (Dan x)))))
  (role-visualize '#{Ana Bob}
                  '(Ana (print (Bob (+ 1 Ana/x))))))
