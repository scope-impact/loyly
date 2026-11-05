"""A minimal JSON-RPC-over-stdio client for the harness seam (.pi/model/rpc.yaml)."""
import json, os, select, signal, subprocess, time


class Harness:
    def __init__(self, start, env=None, budget=30.0):
        self.proc = subprocess.Popen([start], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, start_new_session=True,
                                     env={**os.environ, **(env or {})})
        self.budget = budget
        self.buffer = b""

    def send(self, message):
        self.proc.stdin.write((json.dumps(message) + "\n").encode("utf-8"))
        self.proc.stdin.flush()

    def read(self, timeout):
        """One parsed stdout line, or None when nothing arrives in time. Lines are split from
        raw reads here, so select() and a reader's own buffer never disagree about what is
        waiting (a buffered readline() hid a duplicate response line from select())."""
        deadline = time.monotonic() + timeout
        fd = self.proc.stdout.fileno()
        while b"\n" not in self.buffer:
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            ready, _, _ = select.select([fd], [], [], left)
            if ready:
                chunk = os.read(fd, 65536)
                if not chunk:
                    return None
                self.buffer += chunk
        line, self.buffer = self.buffer.split(b"\n", 1)
        return json.loads(line.decode("utf-8"))

    def call(self, request_id, method, params=None, timeout=None):
        self.send({"id": request_id, "method": method, "params": params or {}})
        return self.read(timeout if timeout is not None else self.budget)

    def stop(self):
        try:
            os.killpg(self.proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(self.proc.pid, signal.SIGKILL)
