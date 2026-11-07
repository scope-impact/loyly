# grade_measurement.exs START_SH: the sealed grader of spec v2 slice 02, the measurement
# context (.pi/spec/measurement/spec.feature, @item-02-001 to @item-02-024).
#
# Authored from the selected Gherkin, the public seam (.pi/spec/measurement/seam.md, with the
# slice's event model and Allium for its params, results and refusals), the three consumed
# handoff contracts (.pi/spec/{specification,cycle-preparation,regeneration-cycle}/event-model/
# handoffs.edn), .pi/spec/decisions.md "Slice 02" and the public calibration protocol
# (AGENTS.md, "The instrument") only. Its author read no candidate, transcript, prior verdict
# or host credential.
#
# For each item it builds fresh state in a temporary directory (a fresh LOYLY_GRAIN_STORE
# file, and the fixture spec files the scenario's Given describes), starts the candidate's
# start.sh there, and drives it only over its JSON-RPC seam. The Given's facts of other
# contexts (an identity the stamper handed out, a pinned cut, a gate that passed) are handed
# in through their seam methods; the Given's earlier commands (a derived checklist, filed
# verdicts, a filed score) go through this context's own methods, and are checked before the
# When. The issuer of measurement/derive-checklist reads the spec (decisions.md "Slice 02" 5):
# where the Given describes spec files, the grader writes them and offers its reading of them,
# Specification's one reading of Gherkin (contract ScenarioReading) implemented here from its
# invariants. The Then is checked from the seam's answers only.
#
# Output: a JSON list on stdout, one {"id", "verdict", "evidence"} per item it reached, in
# item order, the shape harness/score.clj files. An item it could not reach (start.sh did not
# start, or exited or went silent before answering anything) gets no verdict, and is named on
# stderr: score.clj records it as unreached and failed, and never drops it. Exit 0 whenever
# the grading ran; exit 2 on a usage error. Elixir 1.18 or later (its JSON module); no deps.

defmodule Reading do
  # contract ScenarioReading (.pi/spec/specification/allium/context.allium), plus the Rule a
  # scenario sits under: the most recent "Rule:" line above it in the same file, trimmed,
  # reset only at the start of a file; null under none.
  @heading ~r/^\s*(?:Scenario|Scenario Outline):(.*)$/
  @rule ~r/^\s*Rule:\s*(.*?)\s*$/
  @item ~r/@item-(\d+-\d+)/

  # The scenarios of one file's text: [%{line, title, tag (an item id or nil), rule, digest}].
  def scenarios(text) do
    {_, _, scen} =
      text
      |> String.split("\n")
      |> Enum.with_index(1)
      |> Enum.reduce({[], nil, []}, fn {raw, n}, {run, rule, scen} ->
        line = String.trim_trailing(raw, "\r")

        cond do
          String.starts_with?(String.trim(line), "@") ->
            {run ++ [line], rule, scen}

          m = Regex.run(@rule, line) ->
            {[], Enum.at(m, 1), scen}

          m = Regex.run(@heading, line) ->
            [_, rest] = m

            if rest == "",
              do: {[], rule, scen},
              else: {[], rule, scen ++ [%{line: n, title: title(rest), tag: tag(run), rule: rule, digest: digest(line)}]}

          true ->
            {[], rule, scen}
        end
      end)

    scen
  end

  def title(rest) do
    case String.trim(rest) do
      "" -> String.last(rest)
      t -> t
    end
  end

  def tag(run) do
    case Regex.scan(@item, Enum.join(run, " ")) do
      [] -> nil
      all -> all |> List.last() |> Enum.at(1)
    end
  end

  def digest(text), do: "sha256:" <> Base.encode16(:crypto.hash(:sha256, text), case: :lower)

  # The issuer's offer for the cut whose spec lives under root/plan: every slice directory
  # whose name starts with a digit, by name; a slice whose plan.md says "status: planned" or
  # "status: backlog" is skipped and recorded; the others' stamped scenarios are the items and
  # their unstamped ones are listed, in file order.
  def offer(root) do
    dirs =
      Path.join(root, "plan")
      |> File.ls!()
      |> Enum.filter(&Regex.match?(~r/^\d/, &1))
      |> Enum.sort()

    Enum.reduce(dirs, %{"items" => [], "unstamped" => [], "skipped-slices" => []}, fn d, acc ->
      slice = d |> String.split("-") |> hd()

      case status(Path.join([root, "plan", d, "plan.md"])) do
        s when s in ["planned", "backlog"] ->
          %{acc | "skipped-slices" => acc["skipped-slices"] ++ [%{"slice" => slice, "status" => s}]}

        _ ->
          rel = "plan/#{d}/spec.feature"
          path = Path.join(root, rel)
          scen = if File.exists?(path), do: scenarios(File.read!(path)), else: []

          items =
            for s <- scen, s.tag != nil,
                do: %{"item-id" => s.tag, "scenario-digest" => s.digest, "rule" => s.rule, "source" => "#{rel}:#{s.line}", "title" => s.title}

          unstamped = for s <- scen, s.tag == nil, do: %{"source" => "#{rel}:#{s.line}", "title" => s.title}
          %{acc | "items" => acc["items"] ++ items, "unstamped" => acc["unstamped"] ++ unstamped}
      end
    end)
  end

  defp status(plan) do
    with true <- File.exists?(plan),
         [_, s] <- Regex.run(~r/(?m)^status:\s*(\S+)/, File.read!(plan)) do
      s
    else
      _ -> nil
    end
  end
end

defmodule Seam do
  @boot_ms 120_000
  @call_ms 30_000

  defp mono, do: System.monotonic_time(:millisecond)

  def start(start_sh, work) do
    store = Path.join(work, "store.sqlite")
    errlog = Path.join(work, "candidate.stderr")

    port =
      Port.open({:spawn_executable, ~c"/bin/sh"}, [
        :binary,
        :exit_status,
        :use_stdio,
        {:line, 1_048_576},
        {:cd, String.to_charlist(work)},
        {:env, [{~c"LOYLY_GRAIN_STORE", String.to_charlist(store)}, {~c"GATE_STORE", false}]},
        {:args, ["-c", "exec \"$0\" 2>>\"$1\"", start_sh, errlog]}
      ])

    {:os_pid, pid} = Port.info(port, :os_pid)
    Process.put(:seam, %{port: port, pid: pid, answered: false, id: 0, exited: nil})
    Ev.note("started start.sh in #{work} with a fresh LOYLY_GRAIN_STORE")
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
  # What the issuers do and what the grader checks, each step noted as evidence.
  @at "2026-11-07T00:00:00Z"
  @whole "whole-plan"

  def fail!(why), do: throw({:fail, why})

  def put(work, rel, lines) do
    path = Path.join(work, rel)
    File.mkdir_p!(Path.dirname(path))
    File.write!(path, Enum.join(lines, "\n") <> "\n")
    path
  end

  # A fixture self-check: the Given's line numbers are what the scenario says.
  def at!(path, line, text) do
    actual = path |> File.read!() |> String.split("\n") |> Enum.at(line - 1)
    if actual != text, do: raise("grader fixture: line #{line} of #{path} is #{inspect(actual)}")
  end

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

  def refused!({:ok, _}, kind, what), do: fail!("#{what} succeeded; expected the refusal #{kind}")

  def refused!({:error, e}, kind, what) do
    code = is_map(e) && e["code"]
    got = is_map(e) && is_map(e["data"]) && e["data"]["refusal"]
    if code != -32000, do: fail!("#{what}: error code #{inspect(code)}, not the refusal code -32000")
    if got != kind, do: fail!("#{what}: refusal #{inspect(got)}, not #{inspect(kind)}")
    Ev.note("observed: #{what} refused as #{kind}")
    e
  end

  def events(r) do
    case r["events"] do
      l when is_list(l) -> l
      other -> fail!("the result's events are #{Ev.short(other)}, not a list")
    end
  end

  # The one event of type in a command's result.
  def event!(r, type) do
    case Enum.filter(events(r), &(is_map(&1) and &1["type"] == type)) do
      [e] -> e
      l -> fail!("the result holds #{length(l)} #{type} events, not one: #{Ev.short(events(r))}")
    end
  end

  def eq!(got, want, what) do
    if got != want, do: fail!("#{what} is #{Ev.short(got)}, expected #{Ev.short(want)}")
    Ev.note("observed: #{what} is #{Ev.short(want)}")
  end

  # ---- the consumed handoffs, handed in through their seam methods

  def slice_of(id), do: id |> String.split("-") |> hd()
  def src(id), do: "plan/#{slice_of(id)}-context/spec.feature"

  def stamped(ids) do
    for {id, n} <- Enum.with_index(ids, 1) do
      p = %{"item-id" => id, "slice" => slice_of(id), "source" => src(id), "line" => 4 * n + 2, "title" => "scenario #{id}", "stamped-at" => @at}
      given!(rpc("specification/scenario-stamped", p, id), "the stamper's identity #{id}")
    end
  end

  def pinned(cut) do
    Process.put(:pinned, MapSet.put(Process.get(:pinned, MapSet.new()), cut))
    commit = Reading.digest(cut) |> String.replace_prefix("sha256:", "") |> String.slice(0, 40)
    p = %{"cut-id" => cut, "commit" => commit, "tree-digest" => Reading.digest("tree " <> cut), "pinned-at" => @at}
    given!(rpc("cycle-preparation/source-cut-pinned", p, cut), "the pinned cut #{cut}")
  end

  def gated(cycle, cut, candidate) do
    p = %{
      "cycle-id" => cycle, "cut-id" => cut, "attempt" => 1, "candidate-digest" => candidate,
      "boot-budget-seconds" => 120, "boot-polls" => 1, "answered-on-poll" => 1,
      "identity-probe-result" => "ok", "evidence-root" => "evidence/#{cycle}", "passed-at" => @at
    }

    given!(rpc("regeneration-cycle/gate-attempt-passed", p, "#{cycle} #{candidate} at #{cut}"), "gate attempt 1 of #{cycle}")
  end

  # ---- this context's commands and queries

  def derive(cut, offer, label) do
    rpc("measurement/derive-checklist", Map.merge(%{"cut-id" => cut, "selection-id" => @whole}, offer), label)
  end

  # An offered stamped item, as the issuer reads it from slice NN's spec file.
  def item(id, n) do
    %{"item-id" => id, "scenario-digest" => Reading.digest("Scenario: scenario #{id}"), "rule" => nil,
      "source" => "#{src(id)}:#{4 * n + 2}", "title" => "scenario #{id}"}
  end

  def offer(ids), do: %{"items" => for({id, n} <- Enum.with_index(ids, 1), do: item(id, n)), "unstamped" => [], "skipped-slices" => []}

  # Given "the checklist of cut holds ids": pin the cut, hand out any identity not yet handed
  # out, derive the checklist, and check it holds the ids.
  def given_checklist(cut, ids) do
    if not MapSet.member?(Process.get(:pinned, MapSet.new()), cut), do: pinned(cut)
    known = Process.get(:handed, MapSet.new())
    stamped(Enum.reject(ids, &MapSet.member?(known, &1)))
    Process.put(:handed, MapSet.union(known, MapSet.new(ids)))
    r = given!(derive(cut, offer(ids), "#{cut} offering #{Enum.join(ids, ", ")}"), "deriving the checklist of #{cut}")
    e = event!(r, "measurement/checklist-derived")
    got = for i <- e["items"] || [], do: i["item-id"]
    if got != ids, do: fail!("the Given could not be established: the checklist of #{cut} holds #{inspect(got)}")
    e
  end

  def file(cycle, candidate, grader, verdicts, label \\ nil) do
    p = %{"cycle-id" => cycle, "candidate-digest" => candidate, "grader-digest" => grader,
          "approved-grader-digests" => ["G-1"], "verdicts" => verdicts}
    rpc("measurement/file-item-verdicts", p, label || "#{cycle} #{candidate} by #{grader}: #{Ev.short(verdicts)}")
  end

  def score(cycle), do: rpc("measurement/file-score", %{"cycle-id" => cycle, "approved-grader-digests" => ["G-1"]}, cycle)

  def v(id, verdict), do: %{"item-id" => id, "verdict" => verdict, "evidence" => "graded #{id}"}

  # Given "cycle was measured at cut with these verdicts": gated, filed by G-1, scored.
  def given_measured(cycle, cut, verdicts) do
    gated(cycle, cut, "D-1")
    given!(file(cycle, "D-1", "G-1", verdicts), "filing the verdicts of #{cycle}")
    r = given!(score(cycle), "filing the score of #{cycle}")
    event!(r, "measurement/measurement-filed")
  end

  def checklist(cut) do
    r = ok!(rpc("measurement/checklist", %{"cut-id" => cut}, cut), "the checklist query")
    if not Map.has_key?(r, "checklist"), do: fail!("the checklist query answered no checklist key: #{Ev.short(r)}")
    r["checklist"]
  end

  def no_checklist!(cut) do
    case checklist(cut) do
      nil -> Ev.note("observed: no checklist exists for #{cut}")
      c -> fail!("a checklist exists for #{cut}: #{Ev.short(c)}")
    end
  end

  def measurement(cycle) do
    r = ok!(rpc("measurement/cycle-measurement", %{"cycle-id" => cycle}, cycle), "the cycle-measurement query")
    if not Map.has_key?(r, "measurement"), do: fail!("the cycle-measurement query answered no measurement key: #{Ev.short(r)}")
    r["measurement"]
  end

  def awaiting!(cycle) do
    case measurement(cycle) do
      nil -> Ev.note("observed: #{cycle}'s measurement shows no score (still awaiting it)")
      m -> fail!("#{cycle}'s measurement is #{Ev.short(m)}, not awaiting its score")
    end
  end

  # "C-7's measurement has no item verdicts filed": with the cut's checklist derived, filing the
  # score is refused as zero verdicts, and the measurement still awaits its score.
  def nothing_filed!(cycle, cut, ids) do
    if checklist(cut) == nil, do: given_checklist(cut, ids)
    refused!(score(cycle), "zero-verdicts", "filing the score of #{cycle} (to read what was filed)")
    awaiting!(cycle)
  end

  def filed!(cycle) do
    case measurement(cycle) do
      %{"status" => "filed"} = m -> m
      m -> fail!("#{cycle}'s measurement is #{Ev.short(m)}, not filed")
    end
  end

  def scoreboard(cycle) do
    r = ok!(rpc("measurement/scoreboard", %{"cycle-id" => cycle}, cycle), "the scoreboard query")
    if not Map.has_key?(r, "scoreboard"), do: fail!("the scoreboard query answered no scoreboard key: #{Ev.short(r)}")
    r["scoreboard"]
  end

  def board!(cycle) do
    case scoreboard(cycle) do
      b when is_map(b) -> b
      b -> fail!("the scoreboard of #{cycle} is #{Ev.short(b)}: none was reported")
    end
  end

  def item_of(items, id) do
    case Enum.filter(items || [], &(is_map(&1) and &1["item-id"] == id)) do
      [i] -> i
      l -> fail!("the measurement holds #{length(l)} entries for #{id}, not one: #{Ev.short(items)}")
    end
  end

  def item_ids!(e, want, what) do
    got = is_map(e["data"]) && e["data"]["item-ids"]
    if got != want, do: fail!("#{what}: data.item-ids is #{inspect(got)}, not #{inspect(want)}")
    Ev.note("observed: the refusal names #{Enum.join(want, ", ")}")
  end

  def has_net?(m) when is_map(m), do: Enum.any?(m, fn {k, v} -> Regex.match?(~r/(^|-)net($|-)/, to_string(k)) or has_net?(v) end)
  def has_net?(_), do: false
end

defmodule Items do
  import Op

  @steps ["    Given a candidate", "    When the gate boots it", "    Then it answers"]

  def example(work, name, fun) do
    dir = Path.join(work, name)
    File.mkdir_p!(dir)
    Ev.note("example #{name}")
    Process.put(:handed, MapSet.new())
    Process.put(:pinned, MapSet.new())

    try do
      Seam.start(Process.get(:start), dir)
      fun.(dir)
    after
      Seam.stop()
    end
  end

  # The derived checklist, from the command's event and from the query, both checked.
  defp derived!(r, cut, offer) do
    e = event!(ok!(r, "deriving the checklist of #{cut}"), "measurement/checklist-derived")
    eq!(e["cut-id"], cut, "the event's cut-id")
    eq!(e["selection-id"], "whole-plan", "the event's selection-id")
    eq!(e["items"], offer["items"], "the event's stamped items")
    eq!(e["total"], length(offer["items"]), "the event's total")
    d = e["checklist-digest"]
    if not (is_binary(d) and d != ""), do: fail!("the event's checklist-digest is #{inspect(d)}")
    c = checklist(cut)
    if not is_map(c), do: fail!("the checklist query answered #{Ev.short(c)} for #{cut}")
    eq!(c["items"], offer["items"], "the checklist's stamped items")
    eq!(c["total"], length(offer["items"]), "the checklist's total")
    eq!(c["checklist-digest"], d, "the checklist's digest")
    e
  end

  defp spec_file(w, dir, lines, status \\ nil) do
    if status, do: put(w, "plan/#{dir}/plan.md", ["# #{dir}", "", "status: #{status}"])
    put(w, "plan/#{dir}/spec.feature", lines)
  end

  defp stamped_lines(ids), do: Enum.flat_map(ids, &(["", "  @item-#{&1}", "  Scenario: scenario #{&1}"] ++ @steps))

  def i001(w) do
    example(w, "only", fn w ->
      spec_file(w, "03-gate", ["Feature: the gate"] ++ stamped_lines(["03-001", "03-002"]))
      spec_file(w, "04-score", ["Feature: the score"] ++ stamped_lines(["04-001"]))
      Enum.each(["K-3", "K-4", "K-5"], &pinned/1)
      stamped(["03-001", "03-002", "04-001"])
      offer = Reading.offer(w)
      ids = for i <- offer["items"], do: i["item-id"]
      if ids != ["03-001", "03-002", "04-001"], do: raise("grader fixture: read #{inspect(ids)}")
      e = derived!(derive("K-3", offer, "K-3 offering the reading of slices 03 and 04"), "K-3", offer)
      # The digest is the digest of those three stamped items: the same items in the same
      # order give the same digest, different items a different one.
      e4 = derived!(derive("K-4", offer, "K-4 offering the same three items"), "K-4", offer)
      eq!(e4["checklist-digest"], e["checklist-digest"], "K-4's digest of the same three items")
      two = %{offer | "items" => Enum.take(offer["items"], 2)}
      e5 = derived!(derive("K-5", two, "K-5 offering 03-001 and 03-002 only"), "K-5", two)
      if e5["checklist-digest"] == e["checklist-digest"], do: fail!("two different item lists have the same checklist digest")
      Ev.note("observed: a different item list has a different checklist digest")
    end)
  end

  def i002(w) do
    # The file the Given describes (spec round 1, decisions.md): 03-002 under no Rule first,
    # then the Rule "Stamping", under which 03-001 sits on line 6 with a padded title. Under
    # the one reading a Rule runs to the end of its file, so this order is the readable one.
    example(w, "only", fn w ->
      src =
        spec_file(w, "03-stamping",
          ["Feature: stamping", "  @item-03-002", "  Scenario: an earlier scenario", "  Rule: Stamping",
           "    @item-03-001", "    Scenario:  a padded title  "] ++ @steps)

      at!(src, 6, "    Scenario:  a padded title  ")
      pinned("K-3")
      stamped(["03-001", "03-002"])
      offer = Reading.offer(w)

      read = for i <- offer["items"], do: {i["item-id"], i["rule"], i["source"], i["title"]}

      if read != [{"03-002", nil, "plan/03-stamping/spec.feature:3", "an earlier scenario"},
                  {"03-001", "Stamping", "plan/03-stamping/spec.feature:6", "a padded title"}],
         do: raise("grader fixture: read #{inspect(read)}")

      e = derived!(derive("K-3", offer, "K-3 offering the reading of slice 03"), "K-3", offer)

      [first, second] =
        case e["items"] do
          [_, _] = l -> l
          l -> fail!("the checklist holds #{Ev.short(l)}, not two stamped items")
        end

      eq!(first["item-id"], "03-002", "the first item")
      if not (Map.has_key?(first, "rule") and first["rule"] == nil), do: fail!("the first item's rule is #{inspect(Map.get(first, "rule", :absent))}, not null")
      Ev.note("observed: 03-002 has no Rule (null)")
      eq!({second["item-id"], second["rule"], second["title"]}, {"03-001", "Stamping", "a padded title"}, "the second item (id, rule, title)")
      if not (is_binary(second["source"]) and String.ends_with?(second["source"], ":6")), do: fail!("the second item's source #{inspect(second["source"])} is not line 6")
      Ev.note("observed: the second item's source is #{second["source"]}")
      c = checklist("K-3")
      ids = for i <- c["items"], do: i["item-id"]
      eq!(ids, ["03-002", "03-001"], "the checklist's order (never sorted by item identity)")
    end)
  end

  def i003(w) do
    example(w, "only", fn w ->
      spec_file(w, "03-gate", ["Feature: the gate"] ++ stamped_lines(["03-001"]), "planned")
      spec_file(w, "04-score", ["Feature: the score"] ++ stamped_lines(["04-001"]), "backlog")
      spec_file(w, "05-board", ["Feature: the board"] ++ stamped_lines(["05-001", "05-002"]))
      pinned("K-3")
      stamped(["03-001", "04-001", "05-001", "05-002"])
      offer = Reading.offer(w)
      e = derived!(derive("K-3", offer, "K-3 offering the reading of slices 03, 04 and 05"), "K-3", offer)
      eq!(for(i <- e["items"], do: i["item-id"]), ["05-001", "05-002"], "the checklist's items (slice 05's only)")
      eq!(e["skipped-slices"], [%{"slice" => "03", "status" => "planned"}, %{"slice" => "04", "status" => "backlog"}], "the recorded skipped slices")
    end)
  end

  def i004(w) do
    example(w, "only", fn w ->
      spec_file(w, "01-spec", ["Feature: spec"] ++ stamped_lines(["01-001"]) ++ ["", "  Scenario: unstamped one"] ++ @steps)
      spec_file(w, "02-measure", ["Feature: measure", "", "  Scenario: unstamped two"] ++ @steps)
      pinned("K-3")
      stamped(["01-001"])
      offer = Reading.offer(w)
      e = refused!(derive("K-3", offer, "K-3 offering the reading of slices 01 and 02"), "unstamped-scenarios", "deriving the checklist")
      listed = is_map(e["data"]) && e["data"]["unstamped"]
      listed = if is_list(listed), do: Enum.sort_by(listed, &Ev.short/1), else: listed
      eq!(listed, Enum.sort_by(offer["unstamped"], &Ev.short/1), "the refusal's unstamped scenarios")
      for u <- offer["unstamped"], do: Ev.note("listed #{u["title"]} in #{u["source"]}")
      msg = to_string(e["message"])
      if not String.contains?(msg, "specification/stamp-slice"), do: fail!("the refusal's message #{inspect(msg)} does not name the stamper")
      Ev.note("observed: the message names specification/stamp-slice")
      no_checklist!("K-3")
    end)
  end

  def i005(w) do
    example(w, "only", fn w ->
      spec_file(w, "03-gate", ["Feature: the gate"] ++ stamped_lines(["03-001"]), "planned")
      spec_file(w, "04-score", ["Feature: the score"] ++ stamped_lines(["04-001"]), "backlog")
      pinned("K-3")
      stamped(["03-001", "04-001"])
      offer = Reading.offer(w)
      refused!(derive("K-3", offer, "K-3 offering the reading of slices 03 and 04"), "empty-checklist", "deriving the checklist")
      no_checklist!("K-3")
    end)
  end

  def i006(w) do
    example(w, "only", fn w ->
      spec_file(w, "03-gate", ["Feature: the gate"] ++ stamped_lines(["03-001", "03-007"]))
      pinned("K-3")
      stamped(["03-001"])
      offer = Reading.offer(w)
      e = refused!(derive("K-3", offer, "K-3 offering the reading of slice 03"), "item-never-stamped", "deriving the checklist")
      item_ids!(e, ["03-007"], "the refusal")
      no_checklist!("K-3")
    end)
  end

  def i007(w) do
    example(w, "only", fn _ ->
      pinned("K-3")
      stamped(["03-001"])
      offer = %{offer(["03-001"]) | "items" => [%{item("03-001", 1) | "title" => ""}]}
      refused!(derive("K-3", offer, "K-3 offering 03-001 with an empty title"), "empty-title", "deriving the checklist")
      no_checklist!("K-3")
    end)
  end

  def i008(w) do
    example(w, "only", fn _ ->
      pinned("K-3")
      gated("C-7", "K-3", "D-1")
      no_checklist!("K-3")
      refused!(file("C-7", "D-1", "G-1", [v("03-001", "pass")]), "no-checklist", "filing the item verdicts")
      nothing_filed!("C-7", "K-3", ["03-001"])
    end)
  end

  def i009(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001"])
      gated("C-7", "K-3", "D-1")
      refused!(file("C-7", "D-1", "G-1", []), "zero-verdicts", "filing the item verdicts")
      awaiting!("C-7")
      nothing_filed!("C-7", "K-3", ["03-001"])
    end)
  end

  def i010(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001"])
      gated("C-7", "K-3", "D-1")
      vs = [%{"item-id" => "03-001", "verdict" => "maybe"}, %{"item-id" => "09-001", "verdict" => "pass"}]
      refused!(file("C-7", "D-1", "G-1", vs), "bad-verdict-value", "filing the item verdicts")
      nothing_filed!("C-7", "K-3", ["03-001"])
    end)
  end

  def i011(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001"])
      gated("C-7", "K-3", "D-1")
      e = refused!(file("C-7", "D-1", "G-1", [v("03-001", "pass"), v("09-001", "pass")]), "unknown-item", "filing the item verdicts")
      item_ids!(e, ["09-001"], "the refusal")
      nothing_filed!("C-7", "K-3", ["03-001"])
    end)
  end

  def i012(w) do
    example(w, "only", fn _ ->
      c = given_checklist("K-3", ["03-001", "03-002", "04-001"])
      gated("C-7", "K-3", "D-1")
      given!(file("C-7", "D-1", "G-1", [v("04-001", "fail"), v("03-001", "pass"), v("03-002", "pass")]), "filing the verdicts")
      e = event!(ok!(score("C-7"), "filing the score"), "measurement/measurement-filed")
      m = filed!("C-7")

      for {what, x} <- [{"the event's", e}, {"the measurement's", m}] do
        eq!(for(i <- x["items"] || [], do: {i["item-id"], i["verdict"]}), [{"03-001", "pass"}, {"03-002", "pass"}, {"04-001", "fail"}], "#{what} items, in order")
        eq!({x["total"], x["passes"], x["unreached"]}, {3, 2, 0}, "#{what} total, passes and unreached")
      end

      eq!(e["checklist-digest"], c["checklist-digest"], "the measurement's checklist digest (K-3's)")
      eq!(e["grader-digests"], ["G-1"], "the measurement's grader digests")
    end)
  end

  def i013(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001", "03-002", "03-003"])
      gated("C-7", "K-3", "D-1")
      given!(file("C-7", "D-1", "G-1", [v("03-001", "pass")]), "filing the verdicts")
      ok!(score("C-7"), "filing the score")
      m = filed!("C-7")

      for id <- ["03-002", "03-003"] do
        i = item_of(m["items"], id)
        eq!({i["verdict"], i["reason"]}, {"fail", "unreached"}, "#{id}'s verdict and reason")
      end

      eq!({m["total"], m["unreached"]}, {3, 2}, "the total and the unreached")
    end)
  end

  def i014(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001", "03-002"])
      gated("C-7", "K-3", "D-1")
      given!(file("C-7", "D-1", "G-1", [Map.put(v("03-001", "fail"), "reason", "outage"), v("03-002", "fail")]), "filing the verdicts")
      ok!(score("C-7"), "filing the score")
      m = filed!("C-7")
      a = item_of(m["items"], "03-001")
      eq!({a["verdict"], a["reason"]}, {"fail", "outage"}, "03-001's verdict and reason")
      b = item_of(m["items"], "03-002")
      eq!({b["verdict"], b["reason"]}, {"fail", nil}, "03-002's verdict and reason")
    end)
  end

  def i015(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001"])
      gated("C-7", "K-3", "D-1")
      given!(file("C-7", "D-1", "G-1", [v("03-001", "fail"), v("03-001", "pass")]), "filing the verdicts")
      e = event!(ok!(score("C-7"), "filing the score"), "measurement/measurement-filed")
      m = filed!("C-7")
      eq!(item_of(m["items"], "03-001")["verdict"], "pass", "03-001's verdict in the measurement")
      eq!(item_of(e["items"], "03-001")["verdict"], "pass", "03-001's verdict in the filed event")
      eq!(e["superseded"], [%{"item-id" => "03-001", "grader-digest" => "G-1", "verdict" => "fail"}], "the superseded verdicts")
    end)
  end

  def i016(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001"])
      gated("C-7", "K-3", "D-1")
      given!(file("C-7", "D-1", "G-1", [%{"item-id" => "03-001", "verdict" => "pass"}]), "filing the verdicts")
      e = event!(ok!(score("C-7"), "filing the score"), "measurement/measurement-filed")
      m = filed!("C-7")
      a = item_of(m["items"], "03-001")
      eq!({a["verdict"], Map.get(a, "evidence", :absent)}, {"pass", ""}, "03-001's verdict and evidence in the measurement")
      b = item_of(e["items"], "03-001")
      eq!({b["verdict"], Map.get(b, "evidence", :absent)}, {"pass", ""}, "03-001's verdict and evidence in the filed event")
    end)
  end

  def i017(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001"])
      gated("C-7", "K-3", "D-1")
      refused!(score("C-7"), "zero-verdicts", "filing the score")
      awaiting!("C-7")
    end)
  end

  # Its Thens: the policy reported the scoreboard (the query answers it, and C-7 is no longer
  # on the policy's to-do list), 2 passes of 3 at "66.7%", no previous cycle. Spec round 1
  # dropped the step "no command of the build loop reads it" (a property of the build loop,
  # guarantee ToAPersonNeverIntoTheLoop, not an outcome of this report): it is not graded.
  def i018(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001", "03-002", "03-003"])
      given_measured("C-7", "K-3", [v("03-001", "pass"), v("03-002", "pass"), v("03-003", "fail")])
      b = board!("C-7")
      eq!({b["cycle-id"], b["selection-id"], b["passes"], b["total"], b["rate"]}, {"C-7", "whole-plan", 2, 3, "66.7%"}, "the scoreboard (cycle, selection, passes, total, rate)")
      if not (Map.has_key?(b, "previous-cycle-id") and b["previous-cycle-id"] == nil), do: fail!("the scoreboard's previous cycle is #{inspect(Map.get(b, "previous-cycle-id", :absent))}, not null")
      Ev.note("observed: no previous cycle")
      r = ok!(rpc("measurement/measurements-to-report", %{}), "the measurements-to-report query")
      if "C-7" in (r["cycle-ids"] || []), do: fail!("C-7 is still to report: #{Ev.short(r)}")
      Ev.note("observed: C-7 is not left to report")
    end)
  end

  def i019(w) do
    example(w, "only", fn _ ->
      gated("C-8", "K-3", "D-1")
      given!(rpc("measurement/fail-score-stage", %{"cycle-id" => "C-8", "refusal" => "zero-verdicts", "detail" => "no grader filed"}, "C-8"), "failing the score stage of C-8")

      case measurement("C-8") do
        %{"status" => "score-stage-failed"} -> Ev.note("observed: C-8's score stage failed")
        m -> fail!("the Given could not be established: C-8's measurement is #{Ev.short(m)}")
      end

      refused!(rpc("measurement/report-scoreboard", %{"cycle-id" => "C-8"}, "C-8"), "no-measurement-filed", "reporting the scoreboard")

      case scoreboard("C-8") do
        nil -> Ev.note("observed: C-8 has no scoreboard")
        b -> fail!("C-8 has a scoreboard: #{Ev.short(b)}")
      end
    end)
  end

  def i020(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001", "03-002"])
      given_measured("C-5", "K-3", [v("03-001", "pass"), v("03-002", "fail")])
      awaiting_none = measurement("C-6")
      if awaiting_none != nil, do: fail!("the Given could not be established: C-6 has a measurement")
      given_measured("C-7", "K-3", [v("03-001", "pass"), v("03-002", "pass")])
      b = board!("C-7")
      eq!(b["previous-cycle-id"], "C-5", "the scoreboard's previous cycle")
      eq!(b["previous-selection-id"], "whole-plan", "the previous cycle's selection")
    end)
  end

  def i021(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001", "03-002"])
      given_measured("C-6", "K-3", [v("03-001", "pass"), v("03-002", "fail")])
      given_measured("C-7", "K-3", [v("03-001", "fail"), v("03-002", "pass")])
      b = board!("C-7")
      eq!(b["previous-cycle-id"], "C-6", "the scoreboard's previous cycle")
      s = b["shared"]
      if not is_map(s), do: fail!("the scoreboard shows no shared items: #{Ev.short(b)}")
      eq!({s["newly-passing"], s["newly-failing"]}, {["03-002"], ["03-001"]}, "newly passing and newly failing")
      eq!({s["items"], s["previous-rate"], s["rate"]}, {2, "50.0%", "50.0%"}, "the shared items and their rates for C-6 and C-7")
      if has_net?(b), do: fail!("the scoreboard carries a net: #{Ev.short(b)}")
      Ev.note("observed: no net in the scoreboard")
    end)
  end

  def i022(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001", "03-002", "03-003"])
      given_checklist("K-4", ["03-001", "03-002", "03-003", "03-004"])
      given_measured("C-6", "K-3", [v("03-001", "pass"), v("03-002", "pass"), v("03-003", "fail")])
      given_measured("C-7", "K-4", [v("03-001", "pass"), v("03-002", "pass"), v("03-003", "pass"), v("03-004", "fail")])
      b = board!("C-7")
      eq!(b["previous-cycle-id"], "C-6", "C-7's previous cycle")
      r = b["restatement"]
      if not is_map(r), do: fail!("C-7's scoreboard has no restatement: #{Ev.short(b)}")
      eq!({r["previous-cycle-id"], r["previous-total"], r["total"], r["added"], r["dropped"]}, {"C-6", 3, 4, 1, 0}, "the restatement (previous cycle, previous total, total, added, dropped)")
      s = b["shared"]
      if not is_map(s), do: fail!("C-7's scoreboard shows no shared items: #{Ev.short(b)}")
      eq!(s["items"], 3, "the shared items")
      # A cycle whose item set did not move: C-8 at K-4 after C-7.
      given_measured("C-8", "K-4", [v("03-001", "pass"), v("03-002", "pass"), v("03-003", "pass"), v("03-004", "pass")])
      b8 = board!("C-8")
      eq!(b8["previous-cycle-id"], "C-7", "C-8's previous cycle")
      if not (Map.has_key?(b8, "restatement") and b8["restatement"] == nil), do: fail!("C-8's restatement is #{Ev.short(Map.get(b8, "restatement", :absent))}, not null")
      Ev.note("observed: C-8, whose item set did not move, has no restatement")
    end)
  end

  def i023(w) do
    example(w, "only", fn _ ->
      ids = for n <- 1..16, do: "03-" <> String.pad_leading("#{n}", 3, "0")
      given_checklist("K-3", ids)
      given_measured("C-7", "K-3", for({id, n} <- Enum.with_index(ids), do: v(id, if(n < 1, do: "pass", else: "fail"))))
      b = board!("C-7")
      eq!({b["passes"], b["total"], b["rate"]}, {1, 16, "6.2%"}, "C-7's passes, total and rate")
      given_measured("C-8", "K-3", for({id, n} <- Enum.with_index(ids), do: v(id, if(n < 5, do: "pass", else: "fail"))))
      b8 = board!("C-8")
      eq!({b8["passes"], b8["total"], b8["rate"]}, {5, 16, "31.2%"}, "the 5-of-16 measurement's passes, total and rate")
    end)
  end

  def i024(w) do
    example(w, "only", fn _ ->
      given_checklist("K-3", ["03-001"])
      given_checklist("K-4", ["04-001"])
      given_measured("C-6", "K-3", [v("03-001", "pass")])
      given_measured("C-7", "K-4", [v("04-001", "pass")])
      b = board!("C-7")
      eq!(b["previous-cycle-id"], "C-6", "C-7's previous cycle")
      s = b["shared"]
      if not is_map(s), do: fail!("C-7's scoreboard shows no shared items' rates: #{Ev.short(b)}")
      eq!({s["items"], s["previous-rate"], s["rate"]}, {0, "n/a", "n/a"}, "the shared items and their rates")
    end)
  end
end

defmodule Main do
  @items for n <- 1..24, do: String.pad_leading("#{n}", 3, "0")

  defp realdir(dir), do: dir |> then(&System.cmd("pwd", ["-P"], cd: &1)) |> elem(0) |> String.trim()

  def run([start]) do
    Process.put(:start, Path.expand(start))
    base = Path.join(realdir(System.tmp_dir!()), "grade-measurement-#{System.unique_integer([:positive])}")
    File.mkdir_p!(base)

    verdicts =
      for n <- @items, reduce: [] do
        acc ->
          id = "02-" <> n
          Process.put(:ev, [])
          work = Path.join(base, id)
          File.mkdir_p!(work)

          outcome =
            try do
              apply(Items, String.to_atom("i" <> n), [work])
              :pass
            rescue
              e in RuntimeError ->
                if String.starts_with?(e.message, "grader fixture"), do: reraise(e, __STACKTRACE__)
                {:fail, "unexpected answer: " <> Exception.message(e)}

              e ->
                {:fail, "unexpected answer: " <> Exception.message(e)}
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
    IO.puts(:stderr, "usage: elixir grade_measurement.exs START_SH")
    System.halt(2)
  end
end

Main.run(System.argv())
