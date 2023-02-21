(import '[java.util.concurrent Executors Future])

(defn loads-of-tasks
  [concurrency]
  (let [executor   (Executors/newVirtualThreadPerTaskExecutor)
        ;; executor   (Executors/newFixedThreadPool concurrency)
        tasks      (mapv #(fn []
                            (Thread/sleep 1000)
                            %)
                         (range concurrency))
        start-time (System/currentTimeMillis)
        sum        (->> (.invokeAll executor tasks)
                        (map #(.get ^Future %))
                        (reduce +))
        end-time   (System/currentTimeMillis)]
    (println "Blazingly Fast!")
    {:sum     sum
     :time-ms (- end-time start-time)}))

(loads-of-tasks 100000)
