(ns loyly.http-fixture (:require [clojure.java.io :as io]))
(defn port [] (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket)))
(def server "from http.server import HTTPServer, BaseHTTPRequestHandler\nimport sys\nclass Handler(BaseHTTPRequestHandler):\n    count = 0\n    def do_GET(self):\n        Handler.count += 1\n        self.send_response(503 if Handler.count < 4 else 200)\n        self.end_headers()\n        self.wfile.write(b'PRODUCT')\n    def log_message(self, *args): pass\nHTTPServer(('127.0.0.1', int(sys.argv[1])), Handler).serve_forever()\n")
(defn prepare! [root]
  (let [dir (str root "/http-candidate")]
    (.mkdirs (io/file dir))
    (spit (str dir "/server.py") server)
    (spit (str dir "/start.sh") "#!/bin/sh\nexec python3 \"$(dirname \"$0\")/server.py\" \"$1\"\n")
    (spit (str dir "/test.sh") "#!/bin/sh\nprintf writable > impl/suite-output\ntest -f plan/01-demo/spec.feature\n")
    (doseq [f ["start.sh" "test.sh"]] (.setExecutable (io/file dir f) true)) dir))
