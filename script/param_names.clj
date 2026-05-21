(ns param-names
  "Read parameter names for JTS methods from the class file's
   LocalVariableTable attribute. JTS is compiled with -g but not
   -parameters, so the names are in LVT, not in the MethodParameters
   attribute that ASM's visitParameter would surface."
  (:import [java.lang.reflect Constructor Method]
           [org.objectweb.asm ClassReader ClassVisitor MethodVisitor Opcodes Type]))

(def ^:private asm-api Opcodes/ASM9)

(defn- arg-slot-count
  "Slots consumed by an argument type. long and double each take 2."
  [^Type t]
  (case (.getSort t)
    7  2  ; long
    8  2  ; double
    1))

(defn- declared-arg-slots
  "Map slot-index -> arg-position (0-based) for the args declared on this
   method. Skips the implicit `this` at slot 0 for instance methods."
  [^String desc instance?]
  (let [arg-types (Type/getArgumentTypes desc)
        start     (if instance? 1 0)]
    (loop [i 0
           slot start
           m  {}]
      (if (= i (count arg-types))
        m
        (let [t (nth arg-types i)]
          (recur (inc i)
                 (+ slot (arg-slot-count t))
                 (assoc m slot i)))))))

(defn- method-visitor
  "Returns an ASM MethodVisitor that captures the arg-position -> name map
   into `state`. `state` is an atom holding the eventual {key value} map for
   the enclosing class; this visitor writes one entry into it."
  [^String method-name ^String desc instance? state]
  (let [arg-types (Type/getArgumentTypes desc)
        slot->arg (declared-arg-slots desc instance?)
        names     (atom (vec (repeat (count arg-types) nil)))]
    (proxy [MethodVisitor] [asm-api]
      (visitLocalVariable [name _ _ start end index]
        ;; LVT entries spanning the whole method (start label at offset 0)
        ;; are the declared args. Locals introduced later in the body have a
        ;; start label past 0 — filter them out via the slot map.
        (when-let [arg-pos (slot->arg index)]
          (swap! names assoc arg-pos name)))
      (visitEnd []
        (swap! state assoc [method-name desc] @names)))))

(defn- class-visitor [state]
  (proxy [ClassVisitor] [asm-api]
    (visitMethod [access name desc _ _]
      (let [static? (not (zero? (bit-and access Opcodes/ACC_STATIC)))]
        (method-visitor name desc (not static?) state)))))

(defn- class-loader ^ClassLoader [] (.getContextClassLoader (Thread/currentThread)))

(defn param-names-for
  "Return a map of [method-name descriptor] -> [param-name ...] for every
   method on `class-fqn` whose LocalVariableTable carries the names. A
   missing or partially-missing LVT yields nils in the vector; consumers
   should drop the entry or fall back when any name is nil."
  [class-fqn]
  (let [class-name (.replace ^String class-fqn \. \/)
        resource   (str class-name ".class")
        state      (atom {})]
    (with-open [is (.getResourceAsStream (class-loader) resource)]
      (when is
        (.accept (ClassReader. is) (class-visitor state) ClassReader/SKIP_FRAMES)))
    @state))

(defn fully-named?
  "True iff every entry in `names` is non-nil — usable as :param-names."
  [names]
  (and (seq names) (every? some? names)))

(defn method-descriptor
  "Internal-form JVM method descriptor for a java.lang.reflect.Method,
   matching the keys returned by `param-names-for`."
  [^Method m]
  (Type/getMethodDescriptor m))

(defn ctor-descriptor
  "Internal-form JVM method descriptor for a java.lang.reflect.Constructor."
  [^Constructor c]
  (Type/getConstructorDescriptor c))
