# grade_regeneration_cycle.exs START_SH: the sealed grader of spec v2 slice 04, the
# regeneration-cycle context (.pi/spec/regeneration-cycle/spec.feature, @item-04-001 to
# @item-04-038; 04-028 to 04-038 added by spec round 2, reason "spec grew", additions only).
#
# Authored from the selected Gherkin, the public seam (.pi/spec/regeneration-cycle/seam.md, with
# the slice's event model and Allium for its params, results and refusals), the consumed handoff
# contracts (.pi/spec/{cycle-preparation,grader-lifecycle,measurement}/event-model/handoffs.edn),
# .pi/spec/decisions.md "Slice 04" and "Spec round 2" (its readings for graders in item 3) and
# the public calibration protocol (AGENTS.md, "The instrument") only. Its author read no
# candidate, transcript, prior verdict or host credential. A Scenario Outline (04-035) is graded
# row by row, each row from fresh state; the item passes only when every row passes.
#
# The grader plays ORC runtime (decisions.md "Slice 04" 4): the results of what ORC's nodes did
# in a room (the self-check run, the fence probe, the gate room) arrive as the params it offers,
# and the facts of other contexts (a pinned cut, an instrument golden written or moved) are
# handed in through their seam methods. It computes the goldens digest it offers by the seam's
# formula ("The goldens digest") over the goldens it handed in.
#
# For each item it builds fresh state in a temporary directory (a fresh LOYLY_GRAIN_STORE file,
# and LOYLY_SCORING_TOOL_PATHS set only where the Given names the scoring tools, unset
# otherwise), starts the candidate's start.sh there, and drives it only over its JSON-RPC seam.
# It first reads cycle-progress, which must show no cycle in the fresh store; it establishes the
# Given through the seam and checks each step; it issues the When; and it checks the Then from
# the seam's answers only (the command's result or refusal, cycle-progress and
# cycles-to-conclude). The stage clock is never waited for: every budget it offers has stage
# timeouts of an hour, far longer than an item's run (seam.md, "The stage clock").
#
# Output: a JSON list on stdout, one {"id", "verdict", "evidence"} per item it reached, in item
# order, the shape harness/score.clj files. An item it could not reach (start.sh did not start,
# or exited or went silent before answering anything) gets no verdict, and is named on stderr:
# score.clj records it as unreached and failed, and never drops it. Exit 0 whenever the grading
# ran; exit 2 on a usage error. Elixir 1.18 or later (its JSON module); no deps.

defmodule Seam do
  @boot_ms 120_000
  @call_ms 30_000

  defp mono, do: System.monotonic_time(:millisecond)

  def start(start_sh, work, tools) do
    store = Path.join(work, "store.sqlite")
    errlog = Path.join(work, "candidate.stderr")
    tools_env = if tools, do: String.to_charlist(JSON.encode!(tools)), else: false

    port =
      Port.open({:spawn_executable, ~c"/bin/sh"}, [
        :binary,
        :exit_status,
        :use_stdio,
        {:line, 1_048_576},
        {:cd, String.to_charlist(work)},
        {:env,
         [
           {~c"LOYLY_GRAIN_STORE", String.to_charlist(store)},
           {~c"LOYLY_SCORING_TOOL_PATHS", tools_env},
           {~c"LOYLY_SCENARIO_DIGESTS", false},
           {~c"GATE_STORE", false}
         ]},
        {:args, ["-c", "exec \"$0\" 2>>\"$1\"", start_sh, errlog]}
      ])

    {:os_pid, pid} = Port.info(port, :os_pid)
    Process.put(:seam, %{port: port, pid: pid, answered: false, id: 0, exited: nil})

    tools_note =
      if tools, do: "LOYLY_SCORING_TOOL_PATHS #{JSON.encode!(tools)}", else: "LOYLY_SCORING_TOOL_PATHS unset"

    Ev.note("started start.sh in #{work} with a fresh LOYLY_GRAIN_STORE and #{tools_note}")
  end

  defp lost(s, why) do
    if s.answered, do: throw({:fail, why}), else: throw({:unreached, why})
  end

  def call(method, params) do
    s = Process.get(:seam)
    id = s.id + 1
    s = %{s | id: id}
    Process.put(:seam, s)
    if s.exited, do: lost(s, "start.sh had exited (status #{s.exited}) before #{method} was sent")
    req = JSON.encode!(%{"jsonrpc" => "2.0", "id" => id, "method" => method, "params" => params})

    try do
      Port.command(s.port, req <> "\n")
    rescue
      _ -> lost(s, "start.sh was gone before #{method} was sent")
    end

    budget = if s.answered, do: @call_ms, else: @boot_ms

    case recv(s.port, mono() + budget, "") do
      :timeout ->
        lost(s, "no answer to #{method} within #{div(budget, 1000)}s")

      {:exit, st} ->
        Process.put(:seam, %{s | exited: st})
        lost(s, "start.sh exited (status #{st}) before answering #{method}")

      {:line, line} ->
        Process.put(:seam, %{s | answered: true})

        case JSON.decode(line) do
          {:ok, %{"id" => ^id, "result" => r}} ->
            {:ok, r}

          {:ok, %{"id" => ^id, "error" => e}} ->
            {:error, e}

          {:ok, %{"id" => other}} when other != id ->
            throw({:fail, "a response to #{method} carried id #{inspect(other)}, not #{id}"})

          {:ok, other} ->
            throw({:fail, "#{method} was answered with neither result nor error: #{Ev.short(other)}"})

          {:error, _} ->
            throw({:fail, "stdout carried a line that is not JSON: #{String.slice(line, 0, 160)}"})
        end
    end
  end

  defp recv(port, deadline, acc) do
    receive do
      {^port, {:data, {:eol, chunk}}} -> {:line, acc <> chunk}
      {^port, {:data, {:noeol, chunk}}} -> recv(port, deadline, acc <> chunk)
      {^port, {:exit_status, st}} -> {:exit, st}
    after
      max(deadline - mono(), 0) -> :timeout
    end
  end

  def stop do
    case Process.delete(:seam) do
      nil ->
        :ok

      s ->
        pids = descendants(s.pid) ++ [s.pid]
        Enum.each(pids, &signal("TERM", &1))

        try do
          Port.close(s.port)
        rescue
          _ -> :ok
        end

        Enum.reduce_while(1..50, nil, fn _, _ ->
          if Enum.any?(pids, &alive?/1), do: (Process.sleep(100); {:cont, nil}), else: {:halt, nil}
        end)

        Enum.each(pids, &signal("KILL", &1))
        flush(s.port)
    end
  end

  defp descendants(pid) do
    {out, _} = System.cmd("pgrep", ["-P", "#{pid}"])
    kids = out |> String.split() |> Enum.map(&String.to_integer/1)
    Enum.flat_map(kids, &[&1 | descendants(&1)])
  end

  defp signal(sig, pid), do: System.cmd("kill", ["-#{sig}", "#{pid}"], stderr_to_stdout: true)
  defp alive?(pid), do: elem(System.cmd("kill", ["-0", "#{pid}"], stderr_to_stdout: true), 1) == 0

  defp flush(port) do
    receive do
      {^port, _} -> flush(port)
    after
      0 -> :ok
    end
  end
end

defmodule Ev do
  def note(text), do: Process.put(:ev, [text | Process.get(:ev, [])])
  def all, do: Process.get(:ev, []) |> Enum.reverse() |> Enum.join("; ")
  def short(v), do: v |> plain() |> JSON.encode!() |> String.slice(0, 400)
  defp plain(v) when is_tuple(v), do: v |> Tuple.to_list() |> plain()
  defp plain(v) when is_list(v), do: Enum.map(v, &plain/1)
  defp plain(v) when is_map(v), do: Map.new(v, fn {k, x} -> {k, plain(x)} end)
  defp plain(v), do: v
end

defmodule Op do
  # What ORC runtime (the grader) offers and what it checks, each step noted as evidence.
  @at "2026-11-07T00:00:00Z"
  @rc "regeneration-cycle/"
  @cycle "C-7"
  @cut "K-3"
  @room_commit "5d1e2f3a4b5c6d7e8f90a1b2c3d4e5f6a7b8c9d0"
  @budget %{
    "gate-attempt-limit" => 3,
    "max-outer-cycles" => 1,
    "max-continuations" => 3,
    "stage-timeouts-seconds" => %{"fence" => 3600, "regenerate" => 3600, "gate" => 3600, "score" => 3600}
  }

  # The fields of each event's :schema (event-model/*.edn): required, optional.
  @schemas %{
    "harness-self-check-passed" =>
      {~w(cycle-id cut-id occasion attempt harness-digest goldens-digest identity-probe-digest skipped-checks grader-digests budget checked-at), []},
    "harness-self-check-failed" =>
      {~w(cycle-id cut-id occasion attempt budget harness-digest failing-check detail reworded-goldens checked-at), []},
    "clean-room-proved" => {~w(cycle-id cut-id clean-room-commit fenced-paths proved-at), []},
    "candidate-regenerated" =>
      {~w(cycle-id attempt purpose candidate-digest regenerated-at), ~w(parent-candidate-digest)},
    "candidate-installed-in-gate-room" => {~w(cycle-id attempt candidate-digest purpose gate-room installed-at), []},
    "gate-attempt-passed" =>
      {~w(cycle-id cut-id attempt candidate-digest boot-budget-seconds boot-polls answered-on-poll identity-probe-result evidence-root passed-at), []},
    "gate-attempt-failed" =>
      {~w(cycle-id attempt candidate-digest reason boot-budget-seconds boot-polls evidence-root detail failed-at), []},
    "cycle-failed" => {~w(cycle-id stage reason detail concluded-at), ~w(candidate-digest)}
  }

  # The read model regeneration-cycles' fields, plus attempts (seam.md, "A cycle's progress").
  @view_keys ~w(cycle-id cut-id status stage stage-entered-at consecutive-gate-refusals budget harness-digest clean-room-commit candidate-digest gate-attempts failure attempts)

  def cycle, do: @cycle
  # The cycle the commands name: C-7, unless a Given runs a step for another cycle (as_cycle).
  def cyc, do: Process.get(:cycle, @cycle)

  def as_cycle(id, fun) do
    Process.put(:cycle, id)

    try do
      fun.()
    after
      Process.delete(:cycle)
    end
  end

  def cut, do: @cut
  def budget, do: @budget
  def room_commit, do: @room_commit

  def fail!(why), do: throw({:fail, why})

  def answered({:ok, r}), do: "result #{Ev.short(r)}"
  def answered({:error, e}), do: "error #{Ev.short(e)}"

  def rpc(method, params, label \\ nil) do
    resp = Seam.call(method, params)
    Ev.note("sent #{method}#{if label, do: " " <> label, else: ""} -> " <> answered(resp))
    resp
  end

  def ok!({:ok, r}, _what) when is_map(r), do: r
  def ok!({:ok, r}, what), do: fail!("#{what} answered a result that is not an object: #{Ev.short(r)}")
  def ok!({:error, _}, what), do: fail!("#{what} was refused")

  def given!({:ok, r}, _what) when is_map(r), do: r
  def given!(_, what), do: fail!("the Given could not be established: #{what} was not accepted")

  def error!({:ok, _}, code, what), do: fail!("#{what} succeeded; expected the error #{code}")

  def error!({:error, e}, code, what) do
    got = is_map(e) && e["code"]
    if got != code, do: fail!("#{what}: error code #{inspect(got)}, not #{code}")
    e
  end

  def refused!(resp, kind, what) do
    e = error!(resp, -32000, what)
    got = is_map(e["data"]) && e["data"]["refusal"]
    if got != kind, do: fail!("#{what}: refusal #{inspect(got)}, not #{inspect(kind)}")
    Ev.note("observed: #{what} refused as #{kind}")
    e
  end

  # A refusal that names paths (or goldens): data.<key> lists exactly them, and the message
  # names them too (seam.md, "Refusals").
  def names!(e, key, want, what) do
    got = is_map(e["data"]) && e["data"][key]
    if not is_list(got) or Enum.sort(got) != Enum.sort(want), do: fail!("#{what}: data.#{key} is #{Ev.short(got)}, not #{Ev.short(want)}")
    msg = e["message"]

    Enum.each(want, fn p ->
      if not (is_binary(msg) and String.contains?(msg, p)), do: fail!("#{what}: the message #{Ev.short(msg)} does not name #{p}")
    end)

    Ev.note("observed: #{what} names #{key} #{Ev.short(want)} in data and message")
  end

  def events(r) do
    case r["events"] do
      l when is_list(l) -> l
      other -> fail!("the result's events are #{Ev.short(other)}, not a list")
    end
  end

  # The command's events: exactly one, of type.
  def event!(r, short) do
    type = if String.contains?(short, "/"), do: short, else: @rc <> short

    case events(r) do
      [%{"type" => ^type} = e] -> e
      l -> fail!("the result holds #{Ev.short(l)}, not exactly one #{type}")
    end
  end

  # The event carries exactly the fields of its :schema (seam.md, "Methods"), its instants as
  # ISO-8601 text.
  def fields!(e, short) do
    {req, opt} = Map.fetch!(@schemas, short)
    keys = e |> Map.keys() |> Enum.reject(&(&1 == "type"))
    missing = req -- keys
    extra = keys -- (req ++ opt)
    if missing != [] or extra != [], do: fail!("#{short} carries the fields #{Ev.short(Enum.sort(keys))}: missing #{Ev.short(missing)}, not of its schema #{Ev.short(extra)}")

    for k <- keys, String.ends_with?(k, "-at") do
      instant!(e[k], "#{short}'s #{k}")
    end

    Ev.note("observed: #{short} carries exactly the fields of its schema")
  end

  def instant!(v, what) do
    ok = is_binary(v) and match?({:ok, _, _}, DateTime.from_iso8601(v))
    if not ok, do: fail!("#{what} is #{Ev.short(v)}, not an ISO-8601 instant")
  end

  def eq!(got, want, what) do
    if got != want, do: fail!("#{what} is #{Ev.short(got)}, expected #{Ev.short(want)}")
    Ev.note("observed: #{what} is #{Ev.short(want)}")
  end

  def has!(m, kv, what) do
    Enum.each(kv, fn {k, v} -> eq!(m[k], v, "#{what}'s #{k}") end)
  end

  def absent!(m, k, what) do
    if Map.get(m, k) != nil, do: fail!("#{what} shows #{k} #{Ev.short(m[k])}, expected none")
    Ev.note("observed: #{what} shows no #{k}")
  end

  # ---- the consumed handoffs, handed in through their seam methods

  def hex(text), do: :crypto.hash(:sha256, text) |> Base.encode16(case: :lower)

  def pin(cut) do
    p = %{"cut-id" => cut, "commit" => String.slice(hex("commit " <> cut), 0, 40), "tree-digest" => "sha256:" <> hex("tree " <> cut), "pinned-at" => @at}
    event!(given!(rpc("cycle-preparation/source-cut-pinned", p, cut), "pinning #{cut}"), "cycle-preparation/source-cut-pinned")
    Ev.note("given: source cut #{cut} was pinned")
  end

  def golden(id, digest, scenario_digest \\ nil) do
    p = %{"golden-id" => id, "scenario" => "the scenario of #{id}", "scenario-digest" => scenario_digest || "s-" <> id,
          "path" => "harness/oracle/#{id}.golden", "digest" => digest, "written-at" => @at}
    event!(given!(rpc("grader-lifecycle/instrument-golden-written", p, "#{id} at #{digest}"), "writing the golden #{id}"),
           "grader-lifecycle/instrument-golden-written")
    Process.put(:goldens, Map.put(Process.get(:goldens, %{}), id, digest))
    Ev.note("given: the instrument golden #{id} was written at #{digest}; the goldens digest is now #{goldens_digest()}")
  end

  def move(id, to, reason) do
    from = Map.fetch!(Process.get(:goldens), id)
    p = %{"golden-id" => id, "from-digest" => from, "to-digest" => to, "reason" => reason, "additions-only" => true,
          "note" => "the golden moved", "scenario-digest" => "s-" <> id, "moved-at" => @at}
    event!(given!(rpc("grader-lifecycle/instrument-golden-moved", p, "#{id} to #{to} for #{reason}"), "moving the golden #{id}"),
           "grader-lifecycle/instrument-golden-moved")
    Process.put(:goldens, Map.put(Process.get(:goldens), id, to))
    Ev.note("given: the instrument golden #{id} moved to #{to}; the goldens digest is now #{goldens_digest()}")
  end

  # seam.md, "The goldens digest": one "<golden-id> TAB <digest> LF" line per current golden,
  # by golden id (code point order is UTF-8 byte order), SHA-256, lowercase hex.
  def goldens_digest do
    text =
      Process.get(:goldens, %{})
      |> Enum.sort()
      |> Enum.map_join(fn {id, d} -> "#{id}\t#{d}\n" end)

    "sha256:" <> hex(text)
  end

  # ---- this context's commands and queries

  def self_check(over, label) do
    p =
      Map.merge(
        %{"cycle-id" => cyc(), "cut-id" => @cut, "occasion" => "before-fence", "attempt" => 0, "harness-digest" => "H-1",
          "goldens-digest" => goldens_digest(), "identity-probe-digest" => "P-1", "outcome" => "passed",
          "skipped-checks" => [], "missing-goldens" => [], "reworded-goldens" => [], "grader-digests" => ["R-1"],
          "budget" => @budget},
        over
      )

    rpc(@rc <> "record-harness-self-check", p, label)
  end

  def prove(over, label) do
    p =
      Map.merge(
        %{"cycle-id" => cyc(), "cut-id" => @cut, "clean-room-commit" => @room_commit,
          "fenced-paths" => ["harness/score.clj", "harness/cycles"], "root-commits" => 1, "commits" => 1,
          "unmatched-paths" => [], "paths-left-in-tree" => [], "paths-reachable-in-object-graph" => []},
        over
      )

    rpc(@rc <> "prove-clean-room", p, label)
  end

  def regen(attempt, digest, parent, mounted) do
    purpose = if attempt == 1, do: "fresh-generation", else: "verification-repair"
    p = %{"cycle-id" => cyc(), "attempt" => attempt, "purpose" => purpose, "candidate-digest" => digest, "mounted-evidence" => mounted}
    p = if parent, do: Map.put(p, "parent-candidate-digest", parent), else: p
    rpc(@rc <> "record-regenerated-candidate", p, "#{digest} for attempt #{attempt} by #{purpose}, mounting #{Ev.short(mounted)}")
  end

  def install(attempt, digest, room) do
    purpose = if attempt == 1, do: "fresh-generation", else: "verification-repair"
    p = %{"cycle-id" => cyc(), "attempt" => attempt, "candidate-digest" => digest, "purpose" => purpose, "gate-room" => room}
    rpc(@rc <> "install-candidate-in-gate-room", p, "#{digest} in #{room} for attempt #{attempt}")
  end

  # The gate room's result for an attempt, as ORC runtime offers it: a pass by default.
  def gate(attempt, digest, over, label), do: gate_raw(attempt, digest, over, [], label)

  # A failed attempt: no poll answered and no probe result, so ORC runtime leaves those
  # optional params out.
  def gate_failed(attempt, digest, evidence, detail \\ "no answer within 20 polls") do
    over = %{"outcome" => "failed", "reason" => "boot-budget-exhausted", "evidence-root" => evidence, "detail" => detail}
    gate_raw(attempt, digest, over, ["answered-on-poll", "identity-probe-result"], "#{attempt} failed with boot-budget-exhausted, evidence #{evidence}")
  end

  def gate_raw(attempt, digest, over, drop, label) do
    p =
      Map.merge(
        %{"cycle-id" => cyc(), "attempt" => attempt, "candidate-digest" => digest, "harness-digest" => "H-1",
          "outcome" => "passed", "identity-probe-present" => true, "identity-probe-digest" => "P-1",
          "seam-answered-before-boot" => false, "boot-budget-seconds" => 10, "boot-polls" => 20,
          "answered-on-poll" => 2, "identity-probe-result" => "kalibra", "evidence-root" => "E-#{attempt}",
          "detail" => "suite green", "grader-digests" => ["R-1"]},
        over
      )
      |> Map.drop(drop)

    rpc(@rc <> "record-gate-attempt", p, label)
  end

  def fail_cycle(stage, reason, detail) do
    rpc(@rc <> "fail-cycle", %{"cycle-id" => @cycle, "stage" => stage, "reason" => reason, "detail" => detail},
        "at #{stage} for #{reason}, detail #{inspect(detail)}")
  end

  def progress(cycle \\ @cycle) do
    r = ok!(rpc(@rc <> "cycle-progress", %{"cycle-id" => cycle}, cycle), "cycle-progress of #{cycle}")
    if not Map.has_key?(r, "cycle"), do: fail!("cycle-progress of #{cycle} answered #{Ev.short(r)}, with no cycle")

    case r["cycle"] do
      nil -> nil
      m when is_map(m) -> m
      other -> fail!("cycle-progress of #{cycle} answered the cycle #{Ev.short(other)}")
    end
  end

  def view!(cycle \\ @cycle) do
    case progress(cycle) do
      nil -> fail!("cycle-progress shows no cycle #{cycle}")
      m -> m
    end
  end

  def no_cycle!(cycle \\ @cycle) do
    case progress(cycle) do
      nil -> Ev.note("observed: cycle-progress shows no cycle #{cycle}")
      m -> fail!("cycle-progress shows the cycle #{cycle}: #{Ev.short(m)}")
    end
  end

  def attempts!(v) do
    case v["attempts"] do
      l when is_list(l) -> l
      other -> fail!("the cycle's attempts are #{Ev.short(other)}, not a list")
    end
  end

  def row!(v, n) do
    case Enum.filter(attempts!(v), &(is_map(&1) and &1["attempt"] == n)) do
      [r] -> r
      l -> fail!("the cycle's progress shows #{length(l)} rows for attempt #{n}, not one: #{Ev.short(attempts!(v))}")
    end
  end

  def failed_attempts!(v, n) do
    got = Enum.count(attempts!(v), &(is_map(&1) and &1["verdict"] == "failed"))
    eq!(got, n, "the number of failed gate attempts in the cycle's progress")
  end

  # "No score" (seam.md): status failed, the failure shown, and nothing of a score: the view
  # holds only the read model's fields and attempts, no item verdict.
  def no_score!(v, stage, reason) do
    eq!(v["status"], "failed", "C-7's status")

    case v["failure"] do
      %{"stage" => ^stage, "reason" => ^reason} -> Ev.note("observed: C-7's failure is at #{stage} for #{reason}")
      other -> fail!("C-7's failure is #{Ev.short(other)}, not at #{stage} for #{reason}")
    end

    extra = Map.keys(v) -- @view_keys
    if extra != [], do: fail!("the failed cycle's progress shows #{Ev.short(Map.take(v, extra))} beside its failure, a score where there is none")
    Ev.note("observed: C-7 shows as no score: failed, with its failure and nothing of a score")
  end

  def to_conclude!(want) do
    r = ok!(rpc(@rc <> "cycles-to-conclude", %{}), "cycles-to-conclude")
    eq!(r["cycle-ids"], want, "cycles-to-conclude's cycle-ids")
  end

  # ---- the Givens, step by step

  def fresh! do
    r = ok!(rpc(@rc <> "cycle-progress", %{}), "cycle-progress of every cycle")

    case r["cycles"] do
      [] -> Ev.note("given: the fresh store holds no cycle")
      other -> fail!("the Given could not be established: the fresh store shows cycles #{Ev.short(other)}")
    end
  end

  # Given "a passing harness self-check before the fence opened cycle C-7 on cut K-3".
  def opened(over \\ %{}) do
    fresh!()
    pin(@cut)
    golden("checklist", "g-1")
    self_checked(over)
  end

  # The passing self-check before the fence alone, over the cut and goldens already handed in.
  def self_checked(over \\ %{}) do
    e = event!(given!(self_check(over, "before the fence, passed"), "the self-check before the fence"), "harness-self-check-passed")
    if e["cycle-id"] != cyc(), do: fail!("the Given could not be established: the self-check names #{Ev.short(e)}")
    Ev.note("given: harness self-check passed was recorded for #{cyc()} before the fence on K-3")
  end

  def proved do
    event!(given!(prove(%{}, "with one root commit, one commit, every fenced path matched and gone"), "the clean room of #{cyc()}"), "clean-room-proved")
    Ev.note("given: the clean room of #{cyc()} was proved")
  end

  def regenerated(n, digest, parent, mounted) do
    event!(given!(regen(n, digest, parent, mounted), "the candidate #{digest} for attempt #{n}"), "candidate-regenerated")
    Ev.note("given: candidate #{digest} was regenerated for attempt #{n}")
  end

  def installed(n, digest, room) do
    event!(given!(install(n, digest, room), "installing #{digest} for attempt #{n}"), "candidate-installed-in-gate-room")
    Ev.note("given: candidate #{digest} was installed in gate room #{room} for attempt #{n}")
  end

  def gate_checked(n, over \\ %{}) do
    e = event!(given!(self_check(Map.merge(%{"occasion" => "before-gate", "attempt" => n}, over), "before gate attempt #{n}, passed"),
                      "the self-check before gate attempt #{n}"), "harness-self-check-passed")
    if e["attempt"] != n, do: fail!("the Given could not be established: the self-check before the gate reads #{Ev.short(e)}")
    Ev.note("given: the harness self-check before gate attempt #{n} passed")
  end

  def failed_gate(n, digest, evidence) do
    event!(given!(gate_failed(n, digest, evidence), "gate attempt #{n} failed"), "gate-attempt-failed")
    Ev.note("given: gate attempt #{n} failed, evidence #{evidence}")
  end

  # Given "candidate D-1 was installed in gate room G-1 for gate attempt 1 of C-7, and the
  # harness self-check before gate attempt 1 passed".
  def at_gate_1(sc_over \\ %{}) do
    opened()
    proved()
    regenerated(1, "D-1", nil, [])
    installed(1, "D-1", "G-1")
    gate_checked(1, sc_over)
  end

  # The attempt that follows a failed one: a repair of the previous candidate, mounting the
  # failed attempt's gate evidence, installed and self-checked.
  def repaired(n, digest, parent, evidence) do
    regenerated(n, digest, parent, [evidence])
    installed(n, digest, "G-#{n}")
    gate_checked(n)
  end

  # ---- spec round 2 (04-028 to 04-038)

  # A failed attempt for a named reason (suite-red: the suite ran, so the gate room's poll and
  # probe results are left out as for any failed attempt).
  def gate_failed_for(attempt, digest, evidence, reason) do
    over = %{"outcome" => "failed", "reason" => reason, "evidence-root" => evidence, "detail" => "the suite is red"}
    gate_raw(attempt, digest, over, ["answered-on-poll", "identity-probe-result"], "#{attempt} failed with #{reason}, evidence #{evidence}")
  end

  def failed_gate_for(n, digest, evidence, reason) do
    event!(given!(gate_failed_for(n, digest, evidence, reason), "gate attempt #{n} failed with #{reason}"), "gate-attempt-failed")
    Ev.note("given: gate attempt #{n} of #{cyc()} failed with #{reason}, evidence #{evidence}")
  end

  def passed_gate_1 do
    at_gate_1()
    event!(given!(gate(1, "D-1", %{}, "1 passed"), "gate attempt 1 passed"), "gate-attempt-passed")
    Ev.note("given: candidate D-1 of C-7 passed gate attempt 1")
  end

  # "Spent" (decisions.md, "Spec round 2" 3): an attempt with a recorded verdict.
  def spent!(v, n) do
    got = Enum.count(attempts!(v), &(is_map(&1) and Map.get(&1, "verdict") != nil))
    eq!(got, n, "the gate attempts #{v["cycle-id"]} has spent (attempts with a recorded verdict)")
  end

  def pass_cycle do
    rpc(@rc <> "pass-cycle", %{"cycle-id" => cyc()}, cyc())
  end

  # A consumed handoff, handed in through its seam method: the candidate appends the event
  # exactly as given and answers it, nothing added (seam.md, "The consumed handoffs").
  def hand_in(method, p, label) do
    r = ok!(rpc(method, p, label), method)
    eq!(events(r), [Map.put(p, "type", method)], "the events #{method} answered")
    r
  end

  def measurement_filed_params do
    %{"cycle-id" => cyc(), "cut-id" => @cut, "selection-id" => "slice-04", "candidate-digest" => "D-1",
      "checklist-digest" => "sha256:" <> hex("checklist of slice-04"), "grader-digests" => ["R-1"], "total" => 1,
      "passes" => 1, "unreached" => 0,
      "items" => [%{"item-id" => "04-001", "verdict" => "pass", "evidence" => "the cycle opened"}],
      "superseded" => [], "filed-at" => @at}
  end

  def scoreboard_reported_params do
    %{"cycle-id" => cyc(), "selection-id" => "slice-04", "passes" => 1, "total" => 1, "rate" => "100.0",
      "previous-cycle-id" => nil, "previous-selection-id" => nil, "new-items" => ["04-001"], "dropped-items" => [],
      "reported-at" => @at}
  end
end

defmodule Items do
  import Op

  def example(work, name, tools \\ nil, fun) do
    dir = Path.join(work, name)
    File.mkdir_p!(dir)
    Process.put(:goldens, %{})
    Ev.note("example #{name}")

    try do
      Seam.start(Process.get(:start), dir, tools)
      fun.()
    after
      Seam.stop()
    end
  end

  # A passing harness self-check before the fence opens the cycle on a pinned source cut.
  def i001(w) do
    example(w, "only", fn ->
      fresh!()
      pin("K-3")
      golden("checklist", "g-1")
      golden("scoreboard-01", "g-2")
      g1 = goldens_digest()
      resp = self_check(%{"goldens-digest" => g1, "grader-digests" => ["D-1"]}, "before the fence, attempt 0, at #{g1}, grader digests D-1, passed, budget 3")
      e = event!(ok!(resp, "the self-check before the fence"), "harness-self-check-passed")
      fields!(e, "harness-self-check-passed")

      has!(e, %{"cycle-id" => "C-7", "cut-id" => "K-3", "occasion" => "before-fence", "attempt" => 0,
                "harness-digest" => "H-1", "goldens-digest" => g1, "grader-digests" => ["D-1"],
                "identity-probe-digest" => "P-1", "budget" => budget(), "skipped-checks" => []}, "harness-self-check-passed")

      v = view!()
      has!(v, %{"cycle-id" => "C-7", "status" => "running", "stage" => "fence", "cut-id" => "K-3", "budget" => budget(), "gate-attempts" => 0, "attempts" => []}, "C-7's progress")
      eq!(get_in(v, ["budget", "gate-attempt-limit"]), 3, "C-7's budget of gate attempts")
      absent!(v, "candidate-digest", "C-7's progress")
    end)
  end

  # A harness self-check at goldens that are no longer current is refused.
  def i002(w) do
    example(w, "only", fn ->
      fresh!()
      pin("K-3")
      golden("checklist", "g-1")
      g1 = goldens_digest()
      move("checklist", "g-2", "spec-grew")
      resp = self_check(%{"goldens-digest" => g1}, "before the fence at #{g1}, the goldens before the move, passed")
      refused!(resp, "goldens-not-current", "the self-check at goldens that are no longer current")
      no_cycle!()
    end)
  end

  # A golden the harness self-check does not reproduce exactly fails it, by name.
  def i003(w) do
    example(w, "only", fn ->
      fresh!()
      pin("K-3")
      golden("score-refusal", "g-7")
      Ev.note("given: the golden score-refusal expects the refusal reason \"unknown item\"; the tools at H-1 give \"unknown id\"")
      check = "instrument golden score-refusal"
      detail = "expected the refusal reason \"unknown item\", got \"unknown id\""
      resp = self_check(%{"outcome" => "failed", "failing-check" => check, "detail" => detail}, "before the fence, failed at #{inspect(check)}")
      e = event!(ok!(resp, "the failed self-check"), "harness-self-check-failed")
      fields!(e, "harness-self-check-failed")
      has!(e, %{"cycle-id" => "C-7", "occasion" => "before-fence", "harness-digest" => "H-1"}, "harness-self-check-failed")
      fc = e["failing-check"]
      if not (is_binary(fc) and String.contains?(fc, "score-refusal")), do: fail!("the failing check is #{Ev.short(fc)}, which does not name the golden score-refusal")
      Ev.note("observed: the failing check #{inspect(fc)} names the instrument golden score-refusal")
    end)
  end

  # A harness self-check before a gate attempt with no candidate for that attempt is refused.
  def i004(w) do
    example(w, "only", fn ->
      opened()
      proved()
      regenerated(1, "D-1", nil, [])
      before = view!()
      resp = self_check(%{"occasion" => "before-gate", "attempt" => 2}, "before gate attempt 2, passed")
      refused!(resp, "no-candidate-for-attempt", "the self-check before gate attempt 2")
      eq!(view!(), before, "C-7's progress after the refusal")
      # Unchanged: nothing was recorded for attempt 2, so once its candidate exists, the
      # self-check before gate attempt 2 is recorded, not refused as already recorded.
      installed(1, "D-1", "G-1")
      gate_checked(1)
      failed_gate(1, "D-1", "E-1")
      regenerated(2, "D-2", "D-1", ["E-1"])
      e = event!(ok!(self_check(%{"occasion" => "before-gate", "attempt" => 2}, "before gate attempt 2, passed, once its candidate exists"),
                     "the self-check before gate attempt 2 once its candidate exists"), "harness-self-check-passed")
      eq!(e["attempt"], 2, "the later self-check's attempt")
      Ev.note("observed: the refused self-check recorded nothing for attempt 2")
    end)
  end

  # A cycle with scores and no scoreboard golden cannot pass the harness self-check.
  def i005(w) do
    example(w, "only", fn ->
      fresh!()
      pin("K-3")
      golden("checklist", "g-1")
      Ev.note("given: K-3 holds the scores of cycle 01 and no instrument golden for its scoreboard")
      resp = self_check(%{"missing-goldens" => ["scoreboard-01"]}, "before the fence, passed, missing golden scoreboard-01")
      e = refused!(resp, "missing-golden", "the passing self-check with a missing golden")
      names!(e, "goldens", ["scoreboard-01"], "the missing-golden refusal")
      no_cycle!()
    end)
  end

  # An instrument golden with no cycle beside it does not fail the harness self-check.
  def i006(w) do
    example(w, "only", fn ->
      fresh!()
      pin("K-3")
      golden("scoreboard-01", "g-2")
      Ev.note("given: the clean room holds no cycle 01, because the fence removed it; the run finds no golden missing")
      resp = self_check(%{}, "before the fence, passed, no missing golden")
      e = event!(ok!(resp, "the self-check with a golden and no cycle beside it"), "harness-self-check-passed")
      fields!(e, "harness-self-check-passed")
      has!(e, %{"cycle-id" => "C-7", "occasion" => "before-fence"}, "harness-self-check-passed")
      Ev.note("observed: harness-self-check-passed lists no missing golden")
      has!(view!(), %{"status" => "running", "stage" => "fence"}, "C-7's progress")
    end)
  end

  # A scoring tool fenced out of the clean room is a listed skip, not a failure.
  def i007(w) do
    example(w, "only", fn ->
      opened()
      proved()
      regenerated(1, "D-1", nil, [])
      Ev.note("given: the tree the self-check runs in has no scoring tool \"score\": the run lists it as a skipped check")
      resp = self_check(%{"occasion" => "before-gate", "attempt" => 1, "skipped-checks" => ["score"]}, "before gate attempt 1, passed, skipped \"score\"")
      e = event!(ok!(resp, "the self-check with a skipped tool"), "harness-self-check-passed")
      fields!(e, "harness-self-check-passed")
      has!(e, %{"cycle-id" => "C-7", "occasion" => "before-gate", "attempt" => 1, "skipped-checks" => ["score"]}, "harness-self-check-passed")
    end)
  end

  # A clean room is proved when every check of the fence's probe holds.
  def i008(w) do
    example(w, "only", fn ->
      opened()
      fenced = ["harness/score.clj", "harness/cycles"]
      resp = prove(%{"fenced-paths" => fenced}, "one root commit, one commit, fenced #{Ev.short(fenced)} all matched, none left in the tree, none reachable")
      e = event!(ok!(resp, "the clean room"), "clean-room-proved")
      fields!(e, "clean-room-proved")
      has!(e, %{"cycle-id" => "C-7", "cut-id" => "K-3", "clean-room-commit" => room_commit(), "fenced-paths" => fenced}, "clean-room-proved")
      has!(view!(), %{"status" => "running", "stage" => "regenerate", "clean-room-commit" => room_commit()}, "C-7's progress")
    end)
  end

  defp no_room!(stage \\ "fence") do
    v = view!()
    absent!(v, "clean-room-commit", "C-7's progress")
    eq!(v["stage"], stage, "C-7's stage")
  end

  # No clean room is proved after a failed harness self-check.
  def i009(w) do
    example(w, "only", fn ->
      fresh!()
      pin("K-3")
      golden("checklist", "g-1")
      e = event!(given!(self_check(%{"outcome" => "failed", "failing-check" => "golden checklist differs", "detail" => "golden checklist differs"},
                                   "before the fence, failed at \"golden checklist differs\""), "the failed self-check"), "harness-self-check-failed")
      if e["cycle-id"] != "C-7", do: fail!("the Given could not be established: #{Ev.short(e)}")
      refused!(prove(%{}, "from K-3, a probe that holds"), "self-check-not-passed", "the clean room after a failed self-check")
      absent!(view!(), "clean-room-commit", "C-7's progress")
    end)
  end

  defp refused_room(over, label, kind, paths) do
    opened()
    e = refused!(prove(over, label), kind, "the clean room #{label}")
    if paths, do: names!(e, "paths", paths, "the #{kind} refusal")
    no_room!()
  end

  # A fenced path that matches nothing stops the fence.
  def i010(w) do
    example(w, "only", fn ->
      p = "harness/scroe.clj"
      refused_room(%{"fenced-paths" => [p], "unmatched-paths" => [p]}, "with fenced path #{p} unmatched", "fenced-path-matched-nothing", [p])
    end)
  end

  # A clean room with two root commits is refused.
  def i011(w) do
    example(w, "only", fn ->
      refused_room(%{"root-commits" => 2, "commits" => 2}, "with two root commits", "root-commits-not-one", nil)
    end)
  end

  # A clean room whose history is more than one commit is refused.
  def i012(w) do
    example(w, "only", fn ->
      refused_room(%{"commits" => 3}, "with one root commit and three commits", "commits-not-one", nil)
    end)
  end

  # A fenced path left in the working tree is refused.
  def i013(w) do
    example(w, "only", fn ->
      refused_room(%{"paths-left-in-tree" => ["harness/cycles"]}, "with harness/cycles still in the working tree", "fenced-path-left-in-tree", ["harness/cycles"])
    end)
  end

  # A prior candidate reachable in the object graph is refused even when the working tree is clean.
  def i014(w) do
    example(w, "only", fn ->
      fenced = ["harness/score.clj", "harness/cycles", "harness-impl"]
      refused_room(%{"fenced-paths" => fenced, "paths-left-in-tree" => [], "paths-reachable-in-object-graph" => ["harness-impl"]},
                   "with harness-impl absent from the working tree and reachable in the object graph",
                   "fenced-path-reachable-in-object-graph", ["harness-impl"])
    end)
  end

  # A clean room that keeps a scoring tool is refused.
  def i015(w) do
    example(w, "only", ["harness/score.clj", "harness/scoreboard.clj"], fn ->
      Ev.note("given: the scoring tools are harness/score.clj and harness/scoreboard.clj")
      refused_room(%{"fenced-paths" => ["harness/score.clj"]}, "with fenced path harness/score.clj only", "scoring-tool-not-fenced", ["harness/scoreboard.clj"])
    end)
  end

  # A fence with no fenced paths proves no clean room: a schema mismatch (decisions.md "Slice 04" 7).
  def i016(w) do
    example(w, "only", fn ->
      opened()
      error!(prove(%{"fenced-paths" => []}, "with no fenced paths"), -32602, "the clean room with no fenced paths")
      Ev.note("observed: the clean room with no fenced paths is refused as -32602")
      no_room!()
    end)
  end

  # A candidate that responded inside the boot budget passes, with its margin.
  def i017(w) do
    example(w, "only", fn ->
      at_gate_1(%{"harness-digest" => "H-1", "identity-probe-digest" => "P-1", "grader-digests" => ["R-1"]})
      resp = gate(1, "D-1", %{}, "1 passed: 10 seconds as 20 polls, responded on poll 2, identity probe result kalibra, suite green")
      e = event!(ok!(resp, "gate attempt 1"), "gate-attempt-passed")
      fields!(e, "gate-attempt-passed")
      has!(e, %{"cycle-id" => "C-7", "cut-id" => "K-3", "attempt" => 1, "candidate-digest" => "D-1", "boot-budget-seconds" => 10,
                "boot-polls" => 20, "answered-on-poll" => 2, "identity-probe-result" => "kalibra"}, "gate-attempt-passed")
      v = view!()
      has!(v, %{"status" => "running", "stage" => "score", "candidate-digest" => "D-1"}, "C-7's progress")
      has!(row!(v, 1), %{"verdict" => "passed", "boot-margin-polls" => 18}, "attempt 1's row")
    end)
  end

  # A candidate that has not responded when the boot budget runs out fails the attempt.
  def i018(w) do
    example(w, "only", fn ->
      at_gate_1()
      e = event!(ok!(gate_failed(1, "D-1", "E-1"), "gate attempt 1"), "gate-attempt-failed")
      fields!(e, "gate-attempt-failed")
      has!(e, %{"cycle-id" => "C-7", "attempt" => 1, "candidate-digest" => "D-1", "reason" => "boot-budget-exhausted",
                "boot-budget-seconds" => 10, "boot-polls" => 20, "evidence-root" => "E-1"}, "gate-attempt-failed")
      v = view!()
      has!(v, %{"status" => "running", "stage" => "regenerate", "gate-attempts" => 1}, "C-7's progress")
      failed_attempts!(v, 1)
      has!(row!(v, 1), %{"verdict" => "failed", "reason" => "boot-budget-exhausted", "evidence-root" => "E-1"}, "attempt 1's row")
      # E-1 is the gate evidence a repair room for attempt 2 may mount, and only it.
      refused!(regen(2, "D-2", "D-1", ["E-9"]), "evidence-not-mountable", "a repair for attempt 2 mounting E-9, no evidence of this cycle")
      e2 = event!(ok!(regen(2, "D-2", "D-1", ["E-1"]), "a repair for attempt 2 mounting E-1"), "candidate-regenerated")
      has!(e2, %{"attempt" => 2, "purpose" => "verification-repair", "parent-candidate-digest" => "D-1"}, "the repair's candidate-regenerated")
    end)
  end

  # The boot clock covers everything the launch entrypoint does, a build included: the gate
  # room's result is the gate's doing, and the candidate records it as offered.
  def i019(w) do
    example(w, "launch-builds", fn ->
      at_gate_1()
      Ev.note("given: the launch entrypoint of D-1 builds for 30 seconds before it serves its first page; its suite would be green")
      resp = gate_failed(1, "D-1", "E-1", "no answer within 20 polls: the launch entrypoint was still building at 10 seconds")
      e = event!(ok!(resp, "gate attempt 1"), "gate-attempt-failed")
      fields!(e, "gate-attempt-failed")
      has!(e, %{"attempt" => 1, "reason" => "boot-budget-exhausted", "boot-budget-seconds" => 10, "boot-polls" => 20}, "gate-attempt-failed")
      has!(row!(view!(), 1), %{"verdict" => "failed", "reason" => "boot-budget-exhausted"}, "attempt 1's row")
    end)

    example(w, "suite-builds", fn ->
      at_gate_1()
      Ev.note("contrast: the launch entrypoint serves at once and the suite builds for 30 seconds: the gate room answered on poll 2")
      resp = gate(1, "D-1", %{"detail" => "suite green; the suite built for 30 seconds"}, "1 passed: responded on poll 2 of 20, the build inside the suite")
      e = event!(ok!(resp, "gate attempt 1"), "gate-attempt-passed")
      has!(e, %{"attempt" => 1, "answered-on-poll" => 2, "boot-polls" => 20}, "gate-attempt-passed")
      has!(row!(view!(), 1), %{"verdict" => "passed", "boot-margin-polls" => 18}, "attempt 1's row")
    end)
  end

  defp still_awaits! do
    v = view!()
    has!(v, %{"status" => "running", "stage" => "gate", "gate-attempts" => 1}, "C-7's progress")
    r = row!(v, 1)
    absent!(r, "verdict", "attempt 1's row")
    eq!(r["installed?"], true, "attempt 1's installed?")
    # Not spent: the attempt still takes its one verdict.
    event!(ok!(gate(1, "D-1", %{}, "1 passed, as the self-check checked it"), "gate attempt 1 after the refusal"), "gate-attempt-passed")
    Ev.note("observed: gate attempt 1 still took its verdict after the refusal")
  end

  # No candidate is graded over a harness the self-check did not check.
  def i020(w) do
    example(w, "only", fn ->
      at_gate_1(%{"harness-digest" => "H-1"})
      refused!(gate(1, "D-1", %{"harness-digest" => "H-2"}, "1 passed at harness digest H-2"), "no-passing-self-check", "the verdict at H-2")
      still_awaits!()
    end)
  end

  # The gate refuses to grade without the identity probe.
  def i021(w) do
    example(w, "only", fn ->
      at_gate_1()
      refused!(gate(1, "D-1", %{"identity-probe-present" => false}, "1 passed while G-1 has no identity probe"), "identity-probe-missing", "the verdict without the identity probe")
      still_awaits!()
    end)
  end

  # An identity probe that is not the one the self-check checked does not judge the candidate.
  def i022(w) do
    example(w, "only", fn ->
      at_gate_1(%{"identity-probe-digest" => "P-1"})
      Ev.note("given: D-1 brings an identity probe of its own, digest P-9")
      refused!(gate(1, "D-1", %{"identity-probe-digest" => "P-9"}, "1 passed with identity probe digest P-9"), "identity-probe-not-the-instruments", "the verdict with D-1's own probe")
      still_awaits!()
    end)
  end

  # A failed harness self-check before the fence fails the cycle at the fence with no score.
  def i023(w) do
    example(w, "only", fn ->
      fresh!()
      pin("K-3")
      golden("checklist", "g-1")
      check = "instrument golden scoreboard-arithmetic"
      event!(ok!(self_check(%{"outcome" => "failed", "failing-check" => check, "detail" => "the scoreboard's arithmetic differs"},
                            "before the fence, failed at #{inspect(check)}"), "the failed self-check"), "harness-self-check-failed")
      v = view!()
      has!(v, %{"cycle-id" => "C-7", "stage" => "fence"}, "C-7's progress")
      no_score!(v, "fence", "harness-self-check-failed")
      d = get_in(v, ["failure", "detail"])
      if not (is_binary(d) and String.contains?(d, check)), do: fail!("C-7's failure detail #{Ev.short(d)} does not name #{inspect(check)}")
      Ev.note("observed: C-7's failure names #{inspect(check)}")
      absent!(v, "clean-room-commit", "C-7's progress")
      to_conclude!([])
    end)
  end

  # A failed harness self-check before a gate attempt fails the cycle at the gate and spends no attempt.
  def i024(w) do
    example(w, "only", fn ->
      at_gate_1()
      failed_gate(1, "D-1", "E-1")
      regenerated(2, "D-2", "D-1", ["E-1"])
      has!(view!(), %{"status" => "running", "stage" => "gate", "gate-attempts" => 2}, "C-7's progress before the When")
      check = "instrument golden checklist"
      event!(ok!(self_check(%{"occasion" => "before-gate", "attempt" => 2, "outcome" => "failed", "failing-check" => check, "detail" => "differs"},
                            "before gate attempt 2, failed at #{inspect(check)}"), "the failed self-check before gate attempt 2"), "harness-self-check-failed")
      v = view!()
      no_score!(v, "gate", "harness-self-check-failed")
      failed_attempts!(v, 1)
      if row!(v, 2)["installed?"] != false, do: fail!("attempt 2's row shows installed? #{Ev.short(row!(v, 2)["installed?"])}, not false")
      Ev.note("observed: attempt 2's candidate is not installed")
      refused!(install(2, "D-2", "G-2"), "cycle-concluded", "installing D-2 for attempt 2 after the failed self-check")
      failed_attempts!(view!(), 1)
    end)
  end

  # No candidate is regenerated before the clean room is proved.
  def i025(w) do
    example(w, "only", fn ->
      opened()
      refused!(regen(1, "D-1", nil, []), "no-clean-room", "the candidate for attempt 1 before the clean room")
      has!(view!(), %{"status" => "running", "stage" => "fence", "gate-attempts" => 0, "attempts" => []}, "C-7's progress")
    end)
  end

  # The last gate attempt the budget allows fails, and the cycle fails without a score.
  def i026(w) do
    example(w, "only", fn ->
      at_gate_1()
      failed_gate(1, "D-1", "E-1")
      repaired(2, "D-2", "D-1", "E-1")
      failed_gate(2, "D-2", "E-2")
      repaired(3, "D-3", "D-2", "E-2")
      has!(view!(), %{"status" => "running", "stage" => "gate", "gate-attempts" => 3}, "C-7's progress before the When")
      event!(ok!(gate_failed(3, "D-3", "E-3"), "gate attempt 3"), "gate-attempt-failed")
      v = view!()
      no_score!(v, "gate", "gate-attempts-exhausted")
      failed_attempts!(v, 3)
      to_conclude!([])
    end)
  end

  # A refused fence fails the cycle at the fence.
  def i027(w) do
    example(w, "only", fn ->
      opened()
      p = "harness-impl/"
      e = refused!(prove(%{"fenced-paths" => [p], "unmatched-paths" => [p]}, "with fenced path #{p} unmatched"), "fenced-path-matched-nothing", "the clean room")
      Ev.note("given: the clean room of C-7 was refused: #{Ev.short(e["message"])}")
      detail = "fence refused: fenced path harness-impl/ matched nothing"
      e = event!(ok!(fail_cycle("fence", "fence-refused", detail), "failing C-7 at the fence"), "cycle-failed")
      fields!(e, "cycle-failed")
      has!(e, %{"cycle-id" => "C-7", "stage" => "fence", "reason" => "fence-refused", "detail" => detail}, "cycle-failed")
      v = view!()
      no_score!(v, "fence", "fence-refused")
      eq!(get_in(v, ["failure", "detail"]), detail, "C-7's failure detail")
    end)
  end

  # ---- spec round 2, "spec grew" (decisions.md, "Spec round 2"; its readings in item 3)

  # A golden whose scenario was reworded since it was written fails the harness self-check.
  def i028(w) do
    example(w, "only", fn ->
      fresh!()
      pin("K-3")
      golden("checklist", "g-1", "S-1")
      Ev.note("given: the scenario of the golden checklist was reworded: its scenario digest is now S-2; the run finds checklist reworded")
      resp = self_check(%{"reworded-goldens" => ["checklist"]}, "before the fence, passed, reworded golden checklist")
      e = refused!(resp, "reworded-golden", "the passing self-check with a reworded golden")
      names!(e, "goldens", ["checklist"], "the reworded-golden refusal")
      no_cycle!()
    end)
  end

  # The candidate of an attempt is installed in a fresh gate room; "read-only" is what the
  # installation records (decisions.md, "Spec round 2" 3).
  def i029(w) do
    example(w, "only", fn ->
      opened()
      proved()
      regenerated(1, "D-1", nil, [])
      e = event!(ok!(install(1, "D-1", "G-1"), "installing D-1 in G-1"), "candidate-installed-in-gate-room")
      fields!(e, "candidate-installed-in-gate-room")
      has!(e, %{"cycle-id" => "C-7", "attempt" => 1, "candidate-digest" => "D-1", "purpose" => "fresh-generation", "gate-room" => "G-1"},
           "candidate-installed-in-gate-room")
      v = view!()
      has!(v, %{"status" => "running", "stage" => "gate", "gate-attempts" => 1}, "C-7's progress")
      r = row!(v, 1)
      has!(r, %{"candidate-digest" => "D-1", "installed?" => true}, "attempt 1's row")
      absent!(r, "verdict", "attempt 1's row")
      spent!(v, 0)
    end)
  end

  # A cycle never has a fourth gate attempt: attempt 4 is outside the schema, -32602
  # (decisions.md, "Spec round 2" 3).
  def i030(w) do
    example(w, "only", fn ->
      at_gate_1()
      failed_gate_for(1, "D-1", "E-1", "suite-red")
      repaired(2, "D-2", "D-1", "E-1")
      failed_gate_for(2, "D-2", "E-2", "suite-red")
      repaired(3, "D-3", "D-2", "E-2")
      failed_gate_for(3, "D-3", "E-3", "suite-red")
      before = view!()
      error!(install(4, "D-4", "G-4"), -32602, "installing D-4 in G-4 for gate attempt 4")
      Ev.note("observed: the installation for gate attempt 4 is refused as -32602")
      v = view!()
      eq!(v, before, "C-7's progress after the refusal")
      eq!(v["gate-attempts"], 3, "C-7's gate attempts")
      eq!(Enum.map(attempts!(v), &(is_map(&1) && &1["attempt"])), [1, 2, 3], "the attempts of C-7's progress")
      spent!(v, 3)
    end)
  end

  # A replayed verdict records nothing and spends no attempt.
  def i031(w) do
    example(w, "only", fn ->
      at_gate_1()
      failed_gate_for(1, "D-1", "E-1", "suite-red")
      before = view!()
      refused!(gate_failed_for(1, "D-1", "E-1", "suite-red"), "verdict-already-recorded", "gate attempt 1 failed with suite-red again")
      v = view!()
      eq!(v, before, "C-7's progress after the replay")
      has!(row!(v, 1), %{"verdict" => "failed", "reason" => "suite-red"}, "attempt 1's row")
      spent!(v, 1)
      eq!(v["gate-attempts"], 1, "C-7's gate attempts")
    end)
  end

  # A seam that already responded before boot is not graded and spends no attempt.
  def i032(w) do
    example(w, "only", fn ->
      at_gate_1()
      Ev.note("given: an orphan of an earlier run already responds on the public seam of G-1")
      resp = gate(1, "D-1", %{"seam-answered-before-boot" => true}, "1 passed with the seam responding before boot")
      refused!(resp, "seam-answered-before-boot", "the verdict with the seam responding before boot")
      spent!(view!(), 0)
      still_awaits!()
    end)
  end

  # The first candidate is a fresh generation for gate attempt 1, and a claim, not done. The
  # builder's report has no param; the gate room the builder does not control is the one the
  # installation records (decisions.md, "Spec round 2" 3).
  def i033(w) do
    example(w, "only", fn ->
      opened()
      proved()
      resp = regen(1, "D-1", nil, [])
      Ev.note("given: the builder session reports the slice done")
      e = event!(ok!(resp, "the candidate D-1 for attempt 1"), "candidate-regenerated")
      fields!(e, "candidate-regenerated")
      has!(e, %{"cycle-id" => "C-7", "attempt" => 1, "purpose" => "fresh-generation", "candidate-digest" => "D-1"}, "candidate-regenerated")
      v = view!()
      has!(v, %{"status" => "running", "stage" => "gate", "gate-attempts" => 1, "candidate-digest" => "D-1"}, "C-7's progress")
      to_conclude!([])
      # Not done on the claim: no verdict is recorded before the candidate is installed in a
      # gate room, and the cycle cannot conclude passed.
      gate_checked(1)
      refused!(gate(1, "D-1", %{}, "1 passed before D-1 was installed in a gate room"), "not-installed", "a verdict on D-1 before its installation")
      refused!(pass_cycle(), "scoreboard-not-reported", "passing C-7 on the builder's claim")
      v = view!()
      has!(v, %{"status" => "running", "stage" => "gate"}, "C-7's progress")
      absent!(row!(v, 1), "verdict", "attempt 1's row")
      installed(1, "D-1", "G-1")
      event!(ok!(gate(1, "D-1", %{}, "1 passed in G-1"), "gate attempt 1 in G-1"), "gate-attempt-passed")
      has!(row!(view!(), 1), %{"verdict" => "passed"}, "attempt 1's row once recorded in G-1")
    end)
  end

  # No candidate is regenerated beyond the budget's gate-attempt limit.
  def i034(w) do
    example(w, "only", fn ->
      b2 = Map.put(budget(), "gate-attempt-limit", 2)
      opened(%{"budget" => b2})
      proved()
      regenerated(1, "D-1", nil, [])
      installed(1, "D-1", "G-1")
      gate_checked(1, %{"budget" => b2})
      failed_gate(1, "D-1", "E-1")
      regenerated(2, "D-2", "D-1", ["E-1"])
      installed(2, "D-2", "G-2")
      gate_checked(2, %{"budget" => b2})
      failed_gate(2, "D-2", "E-2")
      eq!(get_in(view!(), ["budget", "gate-attempt-limit"]), 2, "C-7's budget of gate attempts")
      refused!(regen(3, "D-3", "D-2", ["E-2"]), "over-gate-attempt-limit", "the candidate for attempt 3 by verification repair")
      v = view!()
      eq!(v["gate-attempts"], 2, "C-7's gate attempts")
      eq!(Enum.map(attempts!(v), &(is_map(&1) && &1["attempt"])), [1, 2], "the attempts of C-7's progress")
    end)
  end

  # Only a repair room mounts evidence, and only this cycle's gate evidence. A Scenario
  # Outline: every row of its Examples is graded, each from fresh state; the item passes only
  # when every row passes.
  def i035(w) do
    rows = [
      {"row-1", fn ->
         opened()
         proved()
         refused!(regen(1, "D-1", nil, ["E-0"]), "evidence-not-mountable", "the candidate for attempt 1 by fresh generation mounting E-0")
         unchanged!(0)
       end},
      {"row-2", fn ->
         at_gate_1()
         failed_gate(1, "D-1", "E-1")
         ev = "score-evidence/C-6"
         Ev.note("given: #{ev} is the score evidence of cycle C-6")
         refused!(regen(2, "D-2", "D-1", [ev]), "evidence-not-mountable", "the repair for attempt 2 mounting the score evidence of C-6")
         unchanged!(1)
       end},
      {"row-3", fn ->
         fresh!()
         pin(cut())
         golden("checklist", "g-1")
         as_cycle("C-6", fn ->
           self_checked()
           proved()
           regenerated(1, "D-1", nil, [])
           installed(1, "D-1", "G-1")
           gate_checked(1)
           failed_gate(1, "D-1", "E-5")
         end)
         Ev.note("given: E-5 is the gate evidence root of cycle C-6")
         self_checked()
         proved()
         regenerated(1, "D-1", nil, [])
         installed(1, "D-1", "G-1")
         gate_checked(1)
         failed_gate(1, "D-1", "E-1")
         refused!(regen(2, "D-2", "D-1", ["E-5"]), "evidence-not-mountable", "the repair for attempt 2 mounting E-5 of cycle C-6")
         unchanged!(1)
       end}
    ]

    failed =
      for {name, fun} <- rows, reduce: [] do
        acc ->
          try do
            example(w, name, fun)
            Ev.note("#{name} passed")
            acc
          catch
            {:fail, why} ->
              Ev.note("#{name} FAILED: #{why}")
              acc ++ ["#{name}: #{why}"]
          end
      end

    if failed != [], do: fail!("#{length(failed)} of #{length(rows)} rows failed: " <> Enum.join(failed, " | "))
  end

  defp unchanged!(n) do
    v = view!()
    has!(v, %{"status" => "running", "stage" => "regenerate", "gate-attempts" => n}, "C-7's progress")
    eq!(length(attempts!(v)), n, "the attempts of C-7's progress")
  end

  # A failed score stage fails the cycle at the score; "no score is retaken" is the cycle's
  # failed status (decisions.md, "Spec round 2" 3).
  def i036(w) do
    example(w, "only", fn ->
      passed_gate_1()
      has!(view!(), %{"status" => "running", "stage" => "score"}, "C-7's progress before the When")
      p = %{"cycle-id" => "C-7", "refusal" => "zero-verdicts", "detail" => "the grader filed zero verdicts", "failed-at" => "2026-11-07T00:00:00Z"}
      hand_in("measurement/score-stage-failed", p, "C-7, refused for zero verdicts")
      v = view!()
      no_score!(v, "score", "score-stage-failed")
      to_conclude!([])
    end)
  end

  # A cycle passes once its scoreboard is reported.
  def i037(w) do
    example(w, "only", fn ->
      passed_gate_1()
      given!(rpc("measurement/measurement-filed", measurement_filed_params(), "C-7"), "the measurement of C-7")
      Ev.note("given: the measurement of C-7 was filed")
      has!(view!(), %{"status" => "running"}, "C-7's progress before the When")
      hand_in("measurement/scoreboard-reported", scoreboard_reported_params(), "C-7")
      v = view!()
      has!(v, %{"status" => "passed", "stage" => "result", "candidate-digest" => "D-1"}, "C-7's progress")
      absent!(v, "failure", "C-7's progress")
      to_conclude!([])
    end)
  end

  # A filed measurement moves the cycle to the scoreboard and nothing else.
  def i038(w) do
    example(w, "only", fn ->
      passed_gate_1()
      before = view!()
      hand_in("measurement/measurement-filed", measurement_filed_params(), "C-7")
      v = view!()
      has!(v, %{"status" => "running", "stage" => "scoreboard", "gate-attempts" => 1, "candidate-digest" => "D-1"}, "C-7's progress")
      eq!(attempts!(v), attempts!(before), "the attempts of C-7's progress")
      spent!(v, 1)
      to_conclude!([])
    end)
  end
end

defmodule Main do
  @items for n <- 1..38, do: String.pad_leading("#{n}", 3, "0")

  defp realdir(dir), do: dir |> then(&System.cmd("pwd", ["-P"], cd: &1)) |> elem(0) |> String.trim()

  def run([start]) do
    Process.put(:start, Path.expand(start))
    base = Path.join(realdir(System.tmp_dir!()), "grade-regeneration-cycle-#{System.unique_integer([:positive])}")
    File.mkdir_p!(base)

    verdicts =
      for n <- @items, reduce: [] do
        acc ->
          id = "04-" <> n
          Process.put(:ev, [])
          work = Path.join(base, id)
          File.mkdir_p!(work)

          outcome =
            try do
              apply(Items, String.to_atom("i" <> n), [work])
              :pass
            rescue
              e -> {:fail, "unexpected answer: " <> Exception.message(e)}
            catch
              {:fail, why} -> {:fail, why}
              {:unreached, why} -> {:unreached, why}
            after
              Seam.stop()
            end

          case outcome do
            :pass ->
              IO.puts(:stderr, "#{id} pass")
              acc ++ [%{"id" => id, "verdict" => "pass", "evidence" => Ev.all()}]

            {:fail, why} ->
              IO.puts(:stderr, "#{id} fail: #{why}")
              acc ++ [%{"id" => id, "verdict" => "fail", "evidence" => Ev.all() <> "; FAILED: " <> why}]

            {:unreached, why} ->
              IO.puts(:stderr, "#{id} unreached, no verdict filed: #{why}")
              acc
          end
      end

    File.rm_rf!(base)
    IO.puts(JSON.encode!(verdicts))
  end

  def run(_) do
    IO.puts(:stderr, "usage: elixir grade_regeneration_cycle.exs START_SH")
    System.halt(2)
  end
end

Main.run(System.argv())
