(ns klor.benchmark
  (:require
   [clojure.string :as str]
   [criterium.core :as crit]
   [dorothy.core :as dot]
   [dorothy.jvm :as dot-jvm]
   [klor.core :refer [defchor]]
   [klor.events :as events]
   [klor.fokkink :as fokkink]
   [klor.fokkink-plain :as fokkink-plain]
   [klor.simulator :as sim]
   [klor.util :refer [do1]]))

;;; Util

(defn process-results [{:keys [mean variance] :as results}]
  (crit/report-result results)
  {:mean (first mean) :stddev (Math/sqrt (first variance))})

(defn format-cell [x & {stddev' :stddev :keys [scale unit]}]
  (if (string? x)
    x
    (let [{:keys [mean stddev]} x
          scale (or scale 1000)]
      (as-> (str (Math/round (* mean scale))) r
        (if stddev' (format "%s ± %s" r (Math/round (* stddev scale))) r)
        (if (and stddev' unit) (format "(%s)" r) r)
        (if unit (format "%s %s" r ({1000 "ms" 1000000 "μs"} scale)) r)))))

(defn format-row [row & {:as opts}]
  (str "| " (str/join " | " (map #(format-cell % opts) row)) " |"))

(defn format-table [[header & rows] & {:as opts}]
  (str (format-row header opts) "\n"
       "|---|" (str/join "|" (repeat (dec (count header)) "---:")) "|\n"
       (str/join "\n" (map #(format-row % opts) rows))))

(defn make-table [rows ns & {:as opts}]
  (cons (cons "Algorithm" (for [n ns] (format "n = %s" n)))
        (for [[name f] rows] (cons name (map f ns)))))

(defn layout-dot [layout]
  (let [layout (map #(mapv keyword (remove (partial = '--) %)) layout)
        stmts (concat [(dot/graph-attrs {:layout :circo :bgcolor :transparent})
                       (dot/node-attrs {:shape :point :color :red})]
                      layout)]
    (dot/dot (dot/graph stmts))))

(defn layout-show [layout]
  (dot-jvm/show! (layout-dot layout)))

(defn layout-save [layout f]
  (dot-jvm/save! (layout-dot layout) f {:format :svg}))

;;; Data

(def roles
  '[A B C D E F G])

(def layouts
  '{3 [(A -- B -- C -- A)]
    4 [(A -- B -- C -- D -- A -- C)]
    5 [(C -- B -- A -- D -- E -- B) (A -- E)]
    6 [(A -- D -- C -- E -- B -- F -- A) (F -- E -- D -- F)]
    7 [(G -- B -- A -- C -- D -- E -- F -- D) (B -- C) (G -- A)]})

(def complete-layouts
  '{3 [(A -- B -- C -- A)]
    4 [(A -- B -- C -- D -- A -- C) (B -- D)]
    5 [(A -- B -- C -- D -- E -- A -- C -- E -- B -- D -- A)]
    6 [(A -- B -- C -- D -- E -- F -- A)
       (B -- D -- F -- B -- E -- C -- A -- E) (A -- D) (C -- F)]
    7 [(A -- B -- C -- D -- E -- F -- G -- A -- C -- E -- G -- B -- D -- F)
       (F -- A -- D -- G -- C -- F -- B -- E -- A)]})

(def ^:dynamic *layouts*
  layouts)

(comment
  (layout-show (layouts 7))

  (layout-show (complete-layouts 7))

  (doseq [[n layout] layouts]
    (layout-save layout (format "/tmp/layout-manual-%s.svg" n)))

  (doseq [[n layout] complete-layouts]
    (layout-save layout (format "/tmp/layout-complete-%s.svg" n)))
  )

;;; Analysis

(defn make-defchor [algo & {:keys [roles layout args]}]
  (let [name (gensym "chor")
        args (when args (repeatedly (count roles) #(gensym "arg")))]
    `(defchor ~name [~@roles] (~'-> ~@(when args roles) [~@roles]) [~@args]
       (~algo [~@roles] ~@(when layout [layout]) ~@args))))

(defn analysis-bench [algo & {:as opts}]
  (let [form (make-defchor algo opts)]
    (process-results
     (crit/with-progress-reporting
       (crit/quick-benchmark (macroexpand form) {})))))

(defn analysis-ring [algo n]
  (analysis-bench algo :roles (take n roles) :args true))

(defn analysis-layout [algo n & {:as kvs}]
  (analysis-bench algo :roles (take n roles) :layout (*layouts* n) kvs))

(def analysis
  [["Chang--Roberts" #(analysis-ring 'fokkink/chang-roberts %)]
   ["Itai--Rodeh" #(analysis-ring 'fokkink/itai-rodeh %)]
   ["Tarry's algorithm" #(analysis-layout 'fokkink/tarry %)]
   ["Depth-first search" #(analysis-layout 'fokkink/dfs %)]
   ["Echo algorithm" #(analysis-layout 'fokkink/echo %)]
   ["Echo w/ extinction" #(analysis-layout 'fokkink/echoex % :args true)]])

(comment
  (let [table (make-table analysis (range 3 8))]
    (do1 table
      (println (format-table table))))
  )

;;; Execution

(defn make-chor [algo & {:keys [args] :as opts}]
  (var-get (eval (make-defchor algo opts))))

(defn execution-bench [algo & {:keys [roles layout args plain] :as opts}]
  (process-results
   (crit/with-progress-reporting
     (if (not plain)
       (let [chor (make-chor algo opts)]
         (binding [sim/*log* false]
           (crit/quick-benchmark @(apply sim/simulate-chor chor args) {})))
       (let [args (if layout (concat [layout] args) args)
             f (resolve algo)]
         (crit/quick-benchmark (apply f roles args) {}))))))

(defn execution-ring [algo n & {:as kvs}]
  (execution-bench algo :roles (take n roles) kvs))

(defn execution-layout [algo n & {:as kvs}]
  (execution-bench algo :roles (take n roles) :layout (*layouts* n) kvs))

(defn make-id-args [n]
  (for [i (range n)] {:id i}))

(defn make-n-args [n]
  (repeat n {:n n}))

(def execution
  [["Chang--Roberts (K)"
    #(execution-ring 'fokkink/chang-roberts % :args (make-id-args %))]
   ["Chang--Roberts (P)"
    #(execution-ring 'fokkink-plain/chang-roberts % :args (make-id-args %)
                     :plain true)]
   ["Itai--Rodeh (K)"
    #(execution-ring 'fokkink/itai-rodeh % :args (make-n-args %))]
   ["Itai--Rodeh (P)"
    #(execution-ring 'fokkink-plain/itai-rodeh % :args (make-n-args %)
                     :plain true)]
   ["Tarry's algorithm (K)"
    #(execution-layout 'fokkink/tarry %)]
   ["Tarry's algorithm (P)"
    #(execution-layout 'fokkink-plain/tarry % :plain true)]
   ["Depth-first search (K)"
    #(execution-layout 'fokkink/dfs %)]
   ["Depth-first search (P)"
    #(execution-layout 'fokkink-plain/dfs % :plain true)]
   ["Echo algorithm (K)" #(execution-layout 'fokkink/echo %)]
   ["Echo algorithm (P)" #(execution-layout 'fokkink-plain/echo % :plain true)]
   ["Echo w/ extinction (K)"
    #(execution-layout 'fokkink/echoex % :args (make-id-args %))]
   ["Echo w/ extinction (P)"
    #(execution-layout 'fokkink-plain/echoex % :args (make-id-args %)
                       :plain true)]])

(comment
  (let [table (make-table execution (range 3 8))]
    (do1 table
      (println (format-table table :scale 1000000))))
  )

;;; Report

(comment
  (def data
    [(make-table analysis (range 3 8))
     (binding [*layouts* complete-layouts]
       (make-table analysis (range 3 8)))
     (make-table execution (range 3 8))
     (binding [*layouts* complete-layouts]
       (make-table execution (range 3 8)))])
  )

(def data
  '[(("Algorithm" "n = 3" "n = 4" "n = 5" "n = 6" "n = 7")
     ("Chang--Roberts"
      {:mean 0.043868971722222226, :stddev 7.166681751423812E-4}
      {:mean 0.05775425875, :stddev 0.003102761089897376}
      {:mean 0.07185281258333333, :stddev 0.0017220195000093054}
      {:mean 0.09097476616666668, :stddev 0.006059518911402064}
      {:mean 0.10142904783333334, :stddev 0.0030456736115655617})
     ("Itai--Rodeh"
      {:mean 0.049809284166666676, :stddev 0.0024728996870616617}
      {:mean 0.07637299433333333, :stddev 0.018499078329983886}
      {:mean 0.07842302175, :stddev 0.005109217922081006}
      {:mean 0.09270517233333334, :stddev 0.0027357132216510086}
      {:mean 0.11274686883333333, :stddev 0.006468136757649847})
     ("Tarry's algorithm"
      {:mean 0.041426406722222225, :stddev 0.0022191086748068168}
      {:mean 0.0575762925, :stddev 0.0029697308589179045}
      {:mean 0.07058338416666668, :stddev 0.0019856150082934606}
      {:mean 0.09128233433333334, :stddev 0.0025875749956057494}
      {:mean 0.10476862066666667, :stddev 0.004129588400623093})
     ("Depth-first search"
      {:mean 0.04149214855555556, :stddev 0.0021056674787345775}
      {:mean 0.05771775058333334, :stddev 0.003090929779375058}
      {:mean 0.06988759441666667, :stddev 0.001361794571870587}
      {:mean 0.09274593883333333, :stddev 0.00301894089973265}
      {:mean 0.1050335485, :stddev 0.004037864273855754})
     ("Echo algorithm"
      {:mean 0.04018338900000001, :stddev 0.0021832686980641216}
      {:mean 0.05604891616666667, :stddev 0.002705692464605701}
      {:mean 0.06870217216666667, :stddev 0.002168823369283218}
      {:mean 0.09083334791666668, :stddev 0.003096303974531965}
      {:mean 0.10052858433333334, :stddev 0.0028476965939837734})
     ("Echo w/ extinction"
      {:mean 0.04332784561111112, :stddev 0.0022542763020283185}
      {:mean 0.06247212491666667, :stddev 0.00282449396184348}
      {:mean 0.07745572508333333, :stddev 0.0021986736752597634}
      {:mean 0.10594045183333334, :stddev 0.005681531269492632}
      {:mean 0.11988398250000001, :stddev 0.005754835117163948}))
    (("Algorithm" "n = 3" "n = 4" "n = 5" "n = 6" "n = 7")
     ("Chang--Roberts"
      {:mean 0.04411405611111111, :stddev 0.002090243362834457}
      {:mean 0.05796363033333334, :stddev 0.004232674436541177}
      {:mean 0.07092910541666668, :stddev 0.0025956891830619397}
      {:mean 0.08655459333333333, :stddev 0.003365377430030464}
      {:mean 0.10352544516666667, :stddev 0.0031229896125125798})
     ("Itai--Rodeh"
      {:mean 0.04964870083333334, :stddev 0.0015116298324218862}
      {:mean 0.06546923658333334, :stddev 0.0031435949062674946}
      {:mean 0.07729511700000001, :stddev 0.0017779755559877192}
      {:mean 0.09284017300000001, :stddev 0.002781037139093568}
      {:mean 0.10984334483333333, :stddev 0.006100955714047715})
     ("Tarry's algorithm"
      {:mean 0.04194919477777778, :stddev 0.0019443004056393556}
      {:mean 0.05912357633333334, :stddev 0.0038488093460962965}
      {:mean 0.0711641115, :stddev 0.0021331344350335813}
      {:mean 0.09293421116666668, :stddev 0.0025203605693446644}
      {:mean 0.10426300266666667, :stddev 0.0039230444969212365})
     ("Depth-first search"
      {:mean 0.04197355116666667, :stddev 0.0019702330780465857}
      {:mean 0.0581079975, :stddev 0.002896101183586713}
      {:mean 0.07037309166666668, :stddev 0.0019664997051742367}
      {:mean 0.09184264458333334, :stddev 0.00251704780471255}
      {:mean 0.10384504916666668, :stddev 0.003847938143876053})
     ("Echo algorithm"
      {:mean 0.040207243833333337, :stddev 0.0020224317906620388}
      {:mean 0.05589798641666667, :stddev 0.0031336877498824764}
      {:mean 0.06884821391666668, :stddev 0.0022635314480281906}
      {:mean 0.08982898666666668, :stddev 0.002956936279767142}
      {:mean 0.10136067033333333, :stddev 0.004445434596498209})
     ("Echo w/ extinction"
      {:mean 0.04367506922222223, :stddev 0.002114373416416595}
      {:mean 0.062044392000000004, :stddev 0.0029194497384374294}
      {:mean 0.07808905475000001, :stddev 0.0018935799809241616}
      {:mean 0.10654756333333333, :stddev 0.005930909299305687}
      {:mean 0.11898895500000001, :stddev 0.005930970825867731}))
    (("Algorithm" "n = 3" "n = 4" "n = 5" "n = 6" "n = 7")
     ("Chang--Roberts (K)"
      {:mean 8.668058452380952E-4, :stddev 1.1467233901534012E-4}
      {:mean 0.001076171179941003, :stddev 1.2782336642740198E-4}
      {:mean 0.0013191710494505496, :stddev 1.400158530215898E-4}
      {:mean 0.0015558578055555555, :stddev 1.5693869286962148E-4}
      {:mean 0.0017664749951690824, :stddev 1.8815395103373526E-4})
     ("Chang--Roberts (P)"
      {:mean 3.673468099415205E-4, :stddev 1.082108042363473E-5}
      {:mean 4.7138204772079775E-4, :stddev 5.973624643473952E-5}
      {:mean 5.677255810055866E-4, :stddev 1.3199514220448476E-5}
      {:mean 7.588185239583333E-4, :stddev 1.1447510584024089E-4}
      {:mean 7.851142530864199E-4, :stddev 3.3588103974892346E-5})
     ("Itai--Rodeh (K)"
      {:mean 0.0010584448879310347, :stddev 8.843998388822794E-5}
      {:mean 0.0012673941376811597, :stddev 1.610372644410434E-4}
      {:mean 0.00175084797008547, :stddev 3.6476544316121543E-4}
      {:mean 0.001831593277777778, :stddev 1.9973875956009698E-4}
      {:mean 0.0021368843274853803, :stddev 2.6317745817427247E-4})
     ("Itai--Rodeh (P)"
      {:mean 5.190189744816588E-4, :stddev 3.506677631950865E-5}
      {:mean 6.767481634615385E-4, :stddev 2.4713859854921706E-5}
      {:mean 8.031653492063493E-4, :stddev 4.891592961800955E-5}
      {:mean 0.001056309241590214, :stddev 3.098307456095241E-4}
      {:mean 0.0010927890336700336, :stddev 5.1485307481274714E-5})
     ("Tarry's algorithm (K)"
      {:mean 0.0010534806076696165, :stddev 1.6139105639324079E-4}
      {:mean 0.0015804169954337902, :stddev 1.997787289376124E-4}
      {:mean 0.0018856538118279572, :stddev 2.2872289589002042E-4}
      {:mean 0.0027720792248062016, :stddev 3.602494944364434E-4}
      {:mean 0.002665669555555556, :stddev 3.1318524253612514E-4})
     ("Tarry's algorithm (P)"
      {:mean 4.099796653439154E-4, :stddev 1.2626528691040714E-5}
      {:mean 5.895099672284645E-4, :stddev 4.222842280692074E-5}
      {:mean 7.548277470023981E-4, :stddev 3.0246152737487163E-5}
      {:mean 0.0011062589029304031, :stddev 1.5681145612498666E-4}
      {:mean 0.0010501379847094802, :stddev 7.863505130733157E-5})
     ("Depth-first search (K)"
      {:mean 0.001060722942942943, :stddev 1.4925742860413875E-4}
      {:mean 0.001610249008888889, :stddev 1.9758749388485014E-4}
      {:mean 0.00186457112962963, :stddev 1.8235177979974756E-4}
      {:mean 0.002743676878787879, :stddev 3.529916448684458E-4}
      {:mean 0.0027061737131782947, :stddev 2.5928596498382884E-4})
     ("Depth-first search (P)"
      {:mean 3.6519296898002103E-4, :stddev 3.4304010657430595E-5}
      {:mean 6.262562572254336E-4, :stddev 1.683080178539808E-5}
      {:mean 7.373230386313466E-4, :stddev 2.1416741266737096E-5}
      {:mean 0.0010491575353535354, :stddev 4.812059272303495E-5}
      {:mean 9.686898157894737E-4, :stddev 8.584252582038266E-5})
     ("Echo algorithm (K)"
      {:mean 7.662361840958606E-4, :stddev 1.536565036598933E-4}
      {:mean 0.0010369557492492494, :stddev 2.036941839160034E-4}
      {:mean 0.0012245460126811597, :stddev 1.7227385775905478E-4}
      {:mean 0.001659147528169014, :stddev 2.6324886417610947E-4}
      {:mean 0.0017928298731343284, :stddev 3.1724102209359465E-4})
     ("Echo algorithm (P)"
      {:mean 2.9311052144249516E-4, :stddev 3.698596059936927E-5}
      {:mean 4.1084800641025645E-4, :stddev 3.980006003384516E-5}
      {:mean 4.717294099859354E-4, :stddev 6.868759969231954E-5}
      {:mean 6.231644036144579E-4, :stddev 5.332168821475116E-5}
      {:mean 7.01163804526749E-4, :stddev 7.180145500363378E-5})
     ("Echo w/ extinction (K)"
      {:mean 0.0014129139983660131, :stddev 3.034628752173258E-4}
      {:mean 0.0016505524785714286, :stddev 2.695339452935773E-4}
      {:mean 0.001916242349726776, :stddev 2.900599670232275E-4}
      {:mean 0.0025763950444444446, :stddev 3.96124715890878E-4}
      {:mean 0.002999549004166667, :stddev 4.1260981844387363E-4})
     ("Echo w/ extinction (P)"
      {:mean 5.395227656514383E-4, :stddev 1.2898966587191774E-5}
      {:mean 8.473531482843138E-4, :stddev 9.576274689867398E-5}
      {:mean 0.0012238230446735396, :stddev 1.4521949048035832E-4}
      {:mean 0.0012045217022471912, :stddev 1.1278875611877915E-4}
      {:mean 0.0015769088799019607, :stddev 8.985770058298281E-5}))
    (("Algorithm" "n = 3" "n = 4" "n = 5" "n = 6" "n = 7")
     ("Chang--Roberts (K)"
      {:mean 8.826166570743406E-4, :stddev 1.2096547520214005E-4}
      {:mean 0.0010784377336309524, :stddev 1.0378717186500166E-4}
      {:mean 0.0012849241431159423, :stddev 1.3711530605424756E-4}
      {:mean 0.0015308167278481014, :stddev 1.4373546127748396E-4}
      {:mean 0.0017506850298507463, :stddev 1.6720822202828976E-4})
     ("Chang--Roberts (P)"
      {:mean 3.7855086594202903E-4, :stddev 2.4632387056023633E-5}
      {:mean 4.678067079710145E-4, :stddev 4.5819148574340984E-5}
      {:mean 5.465012367021276E-4, :stddev 2.865416268942539E-5}
      {:mean 6.820298763102725E-4, :stddev 8.023490409305454E-5}
      {:mean 7.579109141414142E-4, :stddev 2.5098247989295903E-5})
     ("Itai--Rodeh (K)"
      {:mean 9.928012037037036E-4, :stddev 1.2893061970529113E-4}
      {:mean 0.001270174077060932, :stddev 1.4620646577287498E-4}
      {:mean 0.0015369659746835443, :stddev 1.424596402650021E-4}
      {:mean 0.0017871410597014926, :stddev 2.0622100216568558E-4}
      {:mean 0.002069557818452381, :stddev 1.6853505134944635E-4})
     ("Itai--Rodeh (P)"
      {:mean 4.506573699861688E-4, :stddev 6.693854597532116E-5}
      {:mean 6.535885372670807E-4, :stddev 4.2161651551814995E-5}
      {:mean 8.238205413625306E-4, :stddev 2.960631939635994E-5}
      {:mean 9.509279065420562E-4, :stddev 6.0188507618603714E-5}
      {:mean 0.0010772010228070176, :stddev 6.159629994338611E-5})
     ("Tarry's algorithm (K)"
      {:mean 0.0010648257192982457, :stddev 1.432295982087422E-4}
      {:mean 0.0015895971711711713, :stddev 2.1171158741712477E-4}
      {:mean 0.0019026518770491807, :stddev 2.2524517462607036E-4}
      {:mean 0.002760057992248062, :stddev 3.24632573413166E-4}
      {:mean 0.0026877382992424245, :stddev 3.079071903132066E-4})
     ("Tarry's algorithm (P)"
      {:mean 3.514393083864119E-4, :stddev 1.718115310080756E-5}
      {:mean 6.155062175E-4, :stddev 4.253100989896936E-5}
      {:mean 7.294334761904762E-4, :stddev 3.6416644447303334E-5}
      {:mean 0.0010197100226537216, :stddev 5.010551487834031E-5}
      {:mean 0.001068284861666667, :stddev 8.16824582955638E-5})
     ("Depth-first search (K)"
      {:mean 0.0010644652303030305, :stddev 1.4287418042652434E-4}
      {:mean 0.0015841696438356166, :stddev 2.0381796339765285E-4}
      {:mean 0.001898536746031746, :stddev 2.1771354277642444E-4}
      {:mean 0.0027862137348484846, :stddev 3.120877417205386E-4}
      {:mean 0.0027115274147286824, :stddev 3.3126069696535104E-4})
     ("Depth-first search (P)"
      {:mean 3.769091912470025E-4, :stddev 3.6131098670856286E-5}
      {:mean 6.020087682648403E-4, :stddev 5.206810369169388E-5}
      {:mean 6.684245985324948E-4, :stddev 5.714964541039164E-5}
      {:mean 0.0011363593659420293, :stddev 7.642216606787242E-5}
      {:mean 0.0010362020549828177, :stddev 6.785357178052434E-5})
     ("Echo algorithm (K)"
      {:mean 7.8131240397351E-4, :stddev 1.3508159768640146E-4}
      {:mean 0.0010572290803571428, :stddev 2.089597525456892E-4}
      {:mean 0.0018125588904761905, :stddev 3.177726656642501E-4}
      {:mean 0.0016687730531400968, :stddev 2.8046967465434307E-4}
      {:mean 0.0017634497291666668, :stddev 3.392691551391461E-4})
     ("Echo algorithm (P)"
      {:mean 3.282727955182073E-4, :stddev 1.3946962765029272E-5}
      {:mean 4.0372507001239157E-4, :stddev 3.2427464016108096E-5}
      {:mean 4.4662864304993255E-4, :stddev 4.314644777381003E-5}
      {:mean 6.165197768361583E-4, :stddev 8.710619534705665E-5}
      {:mean 6.936924027777778E-4, :stddev 4.747743403737999E-5})
     ("Echo w/ extinction (K)"
      {:mean 0.0011526878990384616, :stddev 1.5035315251145758E-4}
      {:mean 0.0016755328402777779, :stddev 2.4060247486527164E-4}
      {:mean 0.0019162553551912572, :stddev 2.64053964800232E-4}
      {:mean 0.002741293507246377, :stddev 4.998132021110938E-4}
      {:mean 0.0029946366875000002, :stddev 3.752834790207343E-4})
     ("Echo w/ extinction (P)"
      {:mean 5.380120876736111E-4, :stddev 2.0019247557724885E-5}
      {:mean 7.780041815920398E-4, :stddev 5.369522578435221E-5}
      {:mean 9.895484035812672E-4, :stddev 9.955154428292587E-5}
      {:mean 0.0011881870018726594, :stddev 9.234065535198819E-5}
      {:mean 0.001565767485074627, :stddev 1.037882800899461E-4}))])

(comment
  (->> (for [opts [{:stddev true} {:unit true} {:stddev true :unit true}]
             :let [scales [1000 1000 1000000 1000000]]]
         (->> (map #(format-table %1 :scale %2 opts) data scales)
              (str/join "\n\n")
              (str "--- " opts " ---\n\n")))
       (str/join "\n\n")
       println)
  )
