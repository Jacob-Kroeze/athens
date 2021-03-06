(ns athens.keybindings
  (:require
    ["@material-ui/icons" :as mui-icons]
    [athens.db :as db]
    [athens.util :refer [scroll-if-needed get-day scroll-into-view]]
    [cljsjs.react]
    [cljsjs.react.dom]
    [clojure.string :refer [replace-first]]
    [goog.dom :refer [getElement]]
    [goog.dom.selection :refer [setStart setEnd getText setCursorPosition getEndPoints]]
    [goog.events.KeyCodes :refer [isCharacterKey]]
    [re-frame.core :refer [dispatch subscribe]])
  (:import
    (goog.events
      KeyCodes)))


(declare slash-options)


(defn modifier-keys
  [e]
  (let [shift (.. e -shiftKey)
        meta (.. e -metaKey)
        ctrl (.. e -ctrlKey)
        alt (.. e -altKey)]
    {:shift shift :meta meta :ctrl ctrl :alt alt}))


(defn get-end-points
  [e]
  (js->clj (getEndPoints (.. e -target))))


(defn destruct-event
  [e]
  (let [key (.. e -key)
        key-code (.. e -keyCode)
        target (.. e -target)
        value (.. target -value)
        event {:key key :key-code key-code :target target :value value}
        modifiers (modifier-keys e)
        [start end] (get-end-points e)
        selection (getText target)
        head (subs value 0 start)
        tail (subs value end)]
    (merge modifiers event
           {:start start :end end}
           {:head head :tail tail}
           {:selection selection})))


(defn update-query
  ([state head type] (update-query state head "" type))
  ([state head key type]
   (let [query-fn (cond
                    (= type :block) db/search-in-block-content
                    (= type :page)  db/search-in-node-title)
         link-start (cond
                      (= type :block) (count (re-find #".*\(\(" head))
                      (= type :page)  (count (re-find #".*\[\[" head)))
         new-query (str (subs head link-start) key)
         results (query-fn new-query)]
     (swap! state assoc :search/query new-query)
     (swap! state assoc :search/results results))))


(def ARROW-KEYS
  {KeyCodes.UP    :up
   KeyCodes.LEFT  :left
   KeyCodes.DOWN  :down
   KeyCodes.RIGHT :right})


(defn arrow-key-direction
  [e]
  (let [key-code (.. e -keyCode)]
    (ARROW-KEYS key-code)))


(defn block-start?
  [e]
  (let [[start _] (get-end-points e)]
    (zero? start)))


(defn block-end?
  [e]
  (let [{:keys [value end]} (destruct-event e)]
    (= end (count value))))


(defn dec-cycle
  [min max v]
  (if (<= v min) max (dec v)))


(defn inc-cycle
  [min max v]
  (if (>= v max) min (inc v)))


(defn max-idx
  [coll]
  (-> coll count dec))


(defn handle-arrow-key
  "May want to flatten this into multiple handlers."
  [e uid state]
  (let [{:keys [key-code shift target]} (destruct-event e)
        ;; TODO
        top-row?    true
        bottom-row? true
        {:search/keys [results type]} @state
        selected-items @(subscribe [:selected/items])
        direction (arrow-key-direction e)]

    (cond

      ;; items already selected, go up or down
      (and shift (seq selected-items) (= :up direction) (dispatch [:selected/up]))
      (and shift (seq selected-items) (= :down direction) (dispatch [:selected/down]))

      ;; Only select block if leaving block content (up on top row or down on bottom row). Otherwise select text
      (and shift (= :up direction) top-row?) (do
                                               (.. target blur)
                                               (dispatch [:editing/uid nil])
                                               (dispatch [:selected/add-item uid]))

      (and shift (= :down direction) bottom-row?) (do
                                                    (.. target blur)
                                                    (dispatch [:editing/uid nil])
                                                    (dispatch [:selected/add-item uid]))

      (= type :slash) (cond
                        (= :up direction) (do
                                            (.. e preventDefault)
                                            (swap! state update :search/index (partial dec-cycle 0 (max-idx slash-options)))
                                            (let [cur-index (:search/index @state)
                                                  container-el (getElement "slash-menu-container")
                                                  next-el (nth (array-seq (.. container-el -children)) cur-index)]
                                              (scroll-into-view next-el (.. container-el -parentNode) false)))
                        (= :down direction) (do
                                              (.. e preventDefault)
                                              (swap! state update :search/index (partial inc-cycle 0 (max-idx slash-options)))
                                              (let [cur-index (:search/index @state)
                                                    container-el (getElement "slash-menu-container")
                                                    next-el (nth (array-seq (.. container-el -children)) cur-index)]
                                                (scroll-into-view next-el container-el false))))

      (or (= type :page) (= type :block))
      (cond
        (= key-code KeyCodes.UP) (do
                                   (.. e preventDefault)
                                   (swap! state update :search/index (partial dec-cycle 0 (max-idx results)))
                                   (scroll-if-needed (getElement (str "result-" (:search/index @state)))
                                                     (getElement "dropdown-menu")))
        (= key-code KeyCodes.DOWN) (do
                                     (.. e preventDefault)
                                     (swap! state update :search/index (partial inc-cycle 0 (max-idx results)))
                                     (scroll-if-needed (getElement (str "result-" (:search/index @state)))
                                                       (getElement "dropdown-menu"))))
      :else (cond
              (and (= key-code KeyCodes.UP) top-row?) (dispatch [:up uid])
              (and (= key-code KeyCodes.LEFT) (block-start? e)) (dispatch [:left uid])
              (and (= key-code KeyCodes.DOWN) bottom-row?) (dispatch [:down uid])
              (and (= key-code KeyCodes.RIGHT) (block-end? e)) (dispatch [:right uid])))))


(defn handle-tab
  [e uid]
  (.. e preventDefault)
  (let [{:keys [shift]} (destruct-event e)
        ;; xxx: probably makes more sense to pass block value to handler directly
        block-zero? (zero? (:block/order (db/get-block [:block/uid uid])))]
    (cond
      shift (dispatch [:unindent uid])
      :else (when-not block-zero?
              (dispatch [:indent uid])))))


(defn handle-escape
  [e state]
  (.. e preventDefault)
  (prn @state)
  (prn state)
  (cond
    (:search/type @state) (swap! state assoc :search/type nil)
    :else (dispatch [:editing/uid nil])))


;; TODO: some expansions require caret placement after
;; fixme: perhaps not the best place to put this, but need to access from both blocks and keybindings
(def slash-options
  [[mui-icons/Done           "Add Todo"      "{{[[TODO]]}} " "cmd-enter"]
   [mui-icons/Timer          "Current Time"  #(.. (js/Date.) (toLocaleTimeString [] (clj->js {"timeStyle" "short"})))]
   [mui-icons/Today          "Today"         #(str "[[" (:title (get-day 0)) "]] ")]
   [mui-icons/Today          "Tomorrow"      #(str "[[" (:title (get-day -1)) "]]")]
   [mui-icons/Today          "Yesterday"     #(str "[[" (:title (get-day 1)) "]]")]
   [mui-icons/YouTube        "YouTube Embed" "{{[[youtube]]: }}"]
   [mui-icons/DesktopWindows "iframe Embed"  "{{iframe: }}"]])

;;[mui-icons/ "Block Embed" #(str "[[" (:title (get-day 1)) "]]")]
;;[mui-icons/DateRange "Date Picker"]
;;[mui-icons/Attachment "Upload Image or File"]
;;[mui-icons/ExposurePlus1 "Word Count"]


;; TODO: also replace typeahead characters that follow "/". may need event to find selectionStart
(defn select-slash-cmd
  [index state]
  (let [{:keys [atom-string]} @state
        [_ _ expansion _] (slash-options index)
        expand (if (fn? expansion) (expansion) expansion)
        replace-str (subs atom-string 0 (dec (count atom-string)))
        new-str     (str replace-str expand)]
    (swap! state merge {:search/index 0
                        :search/type nil
                        :atom-string  new-str})))


(defn auto-complete
  [state e completed-str]
  (let [{:keys [start head tail target]} (destruct-event e)
        {:search/keys [query type]} @state
        head-pattern (cond
                       (= type :block) (re-pattern (str "(.*)\\(\\(" query))
                       (= type :page)  (re-pattern (str "(.*)\\[\\[" query)))
        tail-pattern (cond
                       (= type :block) #"(\)\))?(.*)"
                       (= type :page)  #"(\]\])?(.*)")
        new-head (cond
                   (= type :block) "$1(("
                   (= type :page)  "$1[[")
        closing-str (cond
                      (= type :block) "))"
                      (= type :page)  "]]")
        new-str (replace-first head head-pattern (str new-head completed-str closing-str))
        [_ closing-delimiter after-closing-str] (re-matches tail-pattern tail)]
    (swap! state merge {:atom-string (str new-str after-closing-str)
                        :search/query nil
                        :search/type nil})
    (when closing-delimiter (set! (. target -selectionStart) (+ 2 start)))))


(defn handle-enter
  [e uid state]
  (let [{:keys [shift meta start head tail value]} (destruct-event e)
        {:search/keys [index results type]} @state]
    (.. e preventDefault)
    (cond
      (= type :slash) (select-slash-cmd index state)

      ;; auto-complete link
      (= type :page)
      (let [{:keys [node/title]} (nth results index)]
        (auto-complete state e title))

      ;; auto-complete block ref
      (= type :block)
      (let [{:keys [block/uid]} (nth results index)]
        (auto-complete state e uid))

      ;; shift-enter: add line break to textarea
      shift (swap! state assoc :atom-string (str head "\n" tail))
      ;; cmd-enter: toggle todo/done
      meta (let [first    (subs value 0 13)
                 new-tail (subs value 13)
                 new-str (cond (= first "{{[[TODO]]}} ") (str "{{[[DONE]]}} " new-tail)
                               (= first "{{[[DONE]]}} ") new-tail
                               :else (str "{{[[TODO]]}} " value))]
             (swap! state assoc :atom-string new-str))
      ;; default: may mutate blocks
      :else (dispatch [:enter uid value start]))))


;; todo: do this for ** and __
(def PAIR-CHARS
  {"(" ")"
   "[" "]"
   "{" "}"
   "\"" "\""})
  ;;"`" "`"
  ;;"*" "*"
   ;;"_" "_"})


(defn surround
  "https://github.com/tpope/vim-surround"
  [selection around]
  (if-let [complement (get PAIR-CHARS around)]
    (str around selection complement)
    (str around selection around)))


;; TODO: it's ctrl for windows and linux right?
(defn handle-shortcuts
  [e _ state]
  (let [{:keys [key-code head tail selection]} (destruct-event e)]
    (cond
      (= key-code KeyCodes.B) (let [new-str (str head (surround selection "**") tail)]
                                (swap! state assoc :atom-string new-str))
      (= key-code KeyCodes.I) (let [new-str (str head (surround selection "__") tail)]
                                (swap! state assoc :atom-string new-str)))))


(defn pair-char?
  [e]
  (let [{:keys [key]} (destruct-event e)
        pair-char-set (-> PAIR-CHARS
                          seq
                          flatten
                          set)]
    (pair-char-set key)))


(defn handle-pair-char
  [e _ state]
  (let [{:keys [key head tail target start end selection]} (destruct-event e)
        close-pair (get PAIR-CHARS key)]
    (cond
      (= start end) (let [new-str (str head key close-pair tail)]
                      (js/setTimeout #(setCursorPosition target (inc start)) 10)
                      (swap! state assoc :atom-string new-str))
      (not= start end) (let [surround-selection (surround selection key)
                             new-str (str head surround-selection tail)]
                         (swap! state assoc :atom-string new-str)
                         (js/setTimeout (fn []
                                          (setStart target (inc start))
                                          (setEnd target (inc end)))
                                        10)))

    ;; this is naive way to begin doing inline search. how to begin search with non-empty parens?
    (let [four-char (subs (:atom-string @state) (dec start) (+ start 3))
          double-brackets? (= "[[]]" four-char)
          double-parens?   (= "(())" four-char)]
      (cond
        double-brackets? (swap! state assoc :search/type :page)
        double-parens? (swap! state assoc :search/type :block)))))

    ;; TODO: close bracket should not be created if it already exists
    ;;(= key-code KeyCodes.CLOSE_SQUARE_BRACKET)


(defn handle-backspace
  [e uid state]
  (let [{:keys [start end value head tail target meta]} (destruct-event e)
        possible-pair (subs value (dec start) (inc start))]

    (cond
      ;; if selection, delete selected text
      (not= start end) (let [new-tail (subs value end)
                             new-str (str head new-tail)]
                         (swap! state assoc :atom-string new-str))

      ;; if meta, delete to start of line
      meta (swap! state assoc :atom-string tail)

      ;; if at block start, dispatch (requires context)
      (block-start? e) (dispatch [:backspace uid value])

      ;; if within brackets, delete close bracket as well
      ;; todo: parameterize, use PAIR-CHARS
      (some #(= possible-pair %) ["[]" "{}" "()"])
      (let [head    (subs value 0 (dec start))
            tail    (subs value (inc start))
            new-str (str head tail)]
        (swap! state assoc :atom-string new-str)
        (swap! state assoc :search/type nil)
        (js/setTimeout #(setCursorPosition target (dec start)) 10))

      ;; default backspace: delete a character
      :else (let [head    (subs value 0 (dec start))
                  new-str (str head tail)
                  {:search/keys [query type]} @state]
              (when (= "/" (last value))
                (swap! state merge {:search/type nil
                                    :search/query nil}))
              (when query
                (update-query state head type))
              (swap! state assoc :atom-string new-str)))))


(defn is-character-key?
  "Closure returns true even when using modifier keys. We do not make that assumption."
  [e]
  (let [{:keys [meta ctrl alt key-code]} (destruct-event e)]
    (and (not meta) (not ctrl) (not alt)
         (isCharacterKey key-code))))


(defn write-char
  [e _ state]
  (let [{:keys [head tail key key-code]} (destruct-event e)
        new-str (str head key tail)
        {:search/keys [type]} @state]
    (cond
      (= key-code KeyCodes.SLASH) (swap! state merge {:search/query ""
                                                      :search/type :slash})

      (= type :slash) (swap! state assoc :search/query new-str)

      ;; when in-line search dropdown is open
      (or (= type :block) (= type :page)) (update-query state head key type))

    (swap! state merge {:atom-string new-str})))


;; XXX: what happens here when we have multi-block selection? In this case we pass in `uids` instead of `uid`
(defn block-key-down
  [e uid state]
  (let [d-event (destruct-event e)
        {:keys [meta ctrl key-code]} d-event]
    (swap! state assoc :last-keydown d-event)
    (cond
      (arrow-key-direction e) (handle-arrow-key e uid state)
      (pair-char? e) (handle-pair-char e uid state)
      (= key-code KeyCodes.TAB) (handle-tab e uid)
      (= key-code KeyCodes.ENTER) (handle-enter e uid state)
      (= key-code KeyCodes.BACKSPACE) (handle-backspace e uid state)
      (= key-code KeyCodes.ESC) (handle-escape e state)
      (or meta ctrl) (handle-shortcuts e uid state))))

;; -- Default: Add new character -----------------------------------------
;(is-character-key? e) (write-char e uid state))))


;;:else (prn "non-event" key key-code))))

