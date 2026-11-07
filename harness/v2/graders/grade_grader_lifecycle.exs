# grade_grader_lifecycle.exs START_SH: the sealed grader of spec v2 slice 03, the
# grader-lifecycle context (.pi/spec/grader-lifecycle/spec.feature, @item-03-001 to
# @item-03-007; 03-005 to 03-007 added for the recorded reason "spec grew", spec round 2).
#
# Authored from the selected Gherkin, the public seam (.pi/spec/grader-lifecycle/seam.md, with
# the slice's event model and Allium for its params, results and refusals), .pi/spec/
# decisions.md "Slice 03" and "Spec round 2" and the public calibration protocol (AGENTS.md, "The instrument")
# only. Its author read no candidate, transcript, prior verdict or host credential.
#
# For each item it builds fresh state in a temporary directory (a fresh LOYLY_GRAIN_STORE file
# and a fresh scenario-digests file named by LOYLY_SCENARIO_DIGESTS), starts the candidate's
# start.sh there, and drives it only over its JSON-RPC seam. The grader is the issuer: it hands
# the candidate its reading of the cut, the current scenario digests. The file holds {} when
# start.sh is launched, and the grader writes the Given's scenario and its digest into it after
# the candidate first answers (the query, which must show no golden in the fresh store) and
# before the first command that checks one: the candidate reads the file at each such command,
# never only at boot (seam.md, "The scenario digests, handed in"). The Given's golden is
# written through grader-lifecycle/write-instrument-golden and checked before the When; the
# Then is checked from the seam's answers only (the command's result or refusal, and the
# grader-lifecycle/instrument-goldens query).
#
# Output: a JSON list on stdout, one {"id", "verdict", "evidence"} per item it reached, in item
# order, the shape harness/score.clj files. An item it could not reach (start.sh did not
# start, or exited or went silent before answering anything) gets no verdict, and is named on
# stderr: score.clj records it as unreached and failed, and never drops it. Exit 0 whenever the
# grading ran; exit 2 on a usage error. Elixir 1.18 or later (its JSON module); no deps.

defmodule Seam do
  @boot_ms 120_000
  @call_ms 30_000

  defp mono, do: System.monotonic_time(:millisecond)

  def start(start_sh, work, digests) do
    store = Path.join(work, "store.sqlite")
    errlog = Path.join(work, "candidate.stderr")

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
           {~c"LOYLY_SCENARIO_DIGESTS", String.to_charlist(digests)},
           {~c"GATE_STORE", false}
         ]},
        {:args, ["-c", "exec \"$0\" 2>>\"$1\"", start_sh, errlog]}
      ])

    {:os_pid, pid} = Port.info(port, :os_pid)
    Process.put(:seam, %{port: port, pid: pid, answered: false, id: 0, exited: nil})
    Ev.note("started start.sh in #{work} with a fresh LOYLY_GRAIN_STORE and LOYLY_SCENARIO_DIGESTS naming a file that holds {}")
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
  # What the issuer does and what the grader checks, each step noted as evidence.
  @written "grader-lifecycle/instrument-golden-written"
  @moved "grader-lifecycle/instrument-golden-moved"

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

  def events(r) do
    case r["events"] do
      l when is_list(l) -> l
      other -> fail!("the result's events are #{Ev.short(other)}, not a list")
    end
  end

  # The command's events: exactly one, of type.
  def event!(r, type) do
    case events(r) do
      [%{"type" => ^type} = e] -> e
      l -> fail!("the result holds #{Ev.short(l)}, not exactly one #{type}")
    end
  end

  def eq!(got, want, what) do
    if got != want, do: fail!("#{what} is #{Ev.short(got)}, expected #{Ev.short(want)}")
    Ev.note("observed: #{what} is #{Ev.short(want)}")
  end

  def instant!(v, what) do
    if not (is_binary(v) and v != ""), do: fail!("#{what} is #{Ev.short(v)}, not an instant")
  end

  # ---- the scenario digests, handed in: the issuer's reading of the cut

  def digests(map) do
    File.write!(Process.get(:digests), JSON.encode!(map))
    Ev.note("handed in the scenario digests #{JSON.encode!(map)}")
  end

  # ---- this context's commands and query

  def write(golden, scenario, sdigest, digest) do
    p = %{"golden-id" => golden, "scenario" => scenario, "scenario-digest" => sdigest,
          "path" => "harness/oracle/#{golden}.golden", "digest" => digest}
    {p, rpc("grader-lifecycle/write-instrument-golden", p, "#{golden} at #{digest} from #{inspect(scenario)} (#{sdigest})")}
  end

  def move(golden, digest, reason, additions, note, sdigest) do
    p = %{"golden-id" => golden, "digest" => digest, "reason" => reason, "additions-only" => additions,
          "note" => note, "scenario-digest" => sdigest}
    rpc("grader-lifecycle/move-instrument-golden", p,
        "#{golden} to #{digest} for #{inspect(reason)}, additions-only #{additions}, note #{inspect(note)}, scenario digest #{sdigest}")
  end

  def goldens do
    r = ok!(rpc("grader-lifecycle/instrument-goldens", %{}), "the instrument-goldens query")

    case r["goldens"] do
      l when is_list(l) -> l
      other -> fail!("the instrument-goldens query answered goldens #{Ev.short(other)}, not a list")
    end
  end

  def golden!(id) do
    case Enum.filter(goldens(), &(is_map(&1) and &1["golden-id"] == id)) do
      [g] -> g
      l -> fail!("the instrument-goldens query shows #{length(l)} goldens #{id}, not one")
    end
  end

  # Given "instrument golden G was written with the digest D from a scenario with the
  # scenario digest S": the issuer hands S in as the scenario's current digest, writes G, and
  # checks the written event and the query.
  # The query goes first: it answers once the candidate is up, so the digests are handed in
  # after boot, as the issuer's reading at the time of the command.
  def given_golden(golden, scenario, sdigest, digest) do
    case goldens() do
      [] -> Ev.note("given: the fresh store holds no golden")
      l -> fail!("the Given could not be established: the fresh store holds goldens #{Ev.short(l)}")
    end

    digests(%{scenario => sdigest})
    {p, resp} = write(golden, scenario, sdigest, digest)
    e = event!(given!(resp, "writing #{golden}"), @written)
    if Map.take(e, Map.keys(p)) != p, do: fail!("the Given could not be established: #{golden} was written as #{Ev.short(e)}")
    g = golden!(golden)
    if {g["digest"], g["moves"]} != {digest, []}, do: fail!("the Given could not be established: #{golden} reads #{Ev.short(g)}")
    Ev.note("given: #{golden} has the digest #{digest}, written from a scenario with the scenario digest #{sdigest}")
  end

  # Then "G still has the digest D": the query shows G at D, with no move recorded.
  def unmoved!(golden, digest) do
    g = golden!(golden)
    eq!(g["digest"], digest, "#{golden}'s digest")
    eq!(g["moves"], [], "#{golden}'s moves")
  end

  # Then "there is no instrument golden G": the query shows no golden G.
  def absent!(golden) do
    l = goldens()
    n = Enum.count(l, &(is_map(&1) and &1["golden-id"] == golden))
    if n != 0, do: fail!("the instrument-goldens query shows #{n} goldens #{golden}: #{Ev.short(l)}")
    Ev.note("observed: the instrument-goldens query shows no golden #{golden}")
  end

  def moved, do: @moved
end

defmodule Items do
  import Op

  @scenario "a refused score names the unknown item"
  @checklist_scenario "the checklist holds one item per stamped scenario"

  def example(work, name, fun) do
    dir = Path.join(work, name)
    File.mkdir_p!(dir)
    digests = Path.join(dir, "scenario-digests.json")
    File.write!(digests, "{}")
    Process.put(:digests, digests)
    Ev.note("example #{name}")

    try do
      Seam.start(Process.get(:start), dir, digests)
      fun.(dir)
    after
      Seam.stop()
    end
  end

  # The instrument maintainer moves a golden for a recorded reason.
  def i001(w) do
    example(w, "only", fn _ ->
      given_golden("G-score-unknown", @scenario, "s-12", "g-40")
      note = "the refusal now names the grader digest"
      r = ok!(move("G-score-unknown", "g-41", "instrument-behavior-changed", false, note, "s-12"), "the move of G-score-unknown")
      e = event!(r, moved())

      eq!(Map.take(e, ["golden-id", "from-digest", "to-digest", "reason", "additions-only", "note", "scenario-digest"]),
          %{"golden-id" => "G-score-unknown", "from-digest" => "g-40", "to-digest" => "g-41",
            "reason" => "instrument-behavior-changed", "additions-only" => false, "note" => note, "scenario-digest" => "s-12"},
          "the move's event")

      instant!(e["moved-at"], "the move's moved-at")
      g = golden!("G-score-unknown")
      eq!(g["digest"], "g-41", "G-score-unknown's digest")
      eq!(g["scenario-digest"], "s-12", "G-score-unknown's scenario digest")

      case g["moves"] do
        [m] when is_map(m) ->
          eq!(Map.take(m, ["from-digest", "to-digest", "reason", "note"]),
              %{"from-digest" => "g-40", "to-digest" => "g-41", "reason" => "instrument-behavior-changed", "note" => note},
              "G-score-unknown's recorded move")

        other ->
          fail!("G-score-unknown's moves are #{Ev.short(other)}, not one move")
      end
    end)
  end

  # "Spec grew" over a diff that is not additions only is refused.
  def i002(w) do
    example(w, "only", fn _ ->
      given_golden("G-checklist", @checklist_scenario, "s-20", "g-50")
      # Its new content changes the title of an existing stamped item: the issuer's reading
      # of that diff is not additions only.
      Ev.note("given: G-checklist's new content changes the title of an existing stamped item, so its diff is not additions only")
      resp = move("G-checklist", "g-51", "spec-grew", false, "the checklist's item 03-002 has a new title", "s-20")
      refused!(resp, "spec-grew-not-additions-only", "the spec-grew move of G-checklist")
      unmoved!("G-checklist", "g-50")
    end)
  end

  # A move without a note is refused.
  def i003(w) do
    example(w, "only", fn _ ->
      given_golden("G-score-unknown", @scenario, "s-12", "g-40")
      resp = move("G-score-unknown", "g-41", "instrument-behavior-changed", false, "", "s-12")
      refused!(resp, "empty-note", "the move of G-score-unknown with an empty note")
      unmoved!("G-score-unknown", "g-40")
    end)
  end

  # A golden is never moved for a reason outside the three: the enumeration is the refusal,
  # heard as params that do not match the schema (seam.md; decisions.md "Slice 03" 4).
  def i004(w) do
    example(w, "only", fn _ ->
      given_golden("G-score-unknown", @scenario, "s-12", "g-40")
      resp = move("G-score-unknown", "g-41", "make-the-self-check-green", false, "it was red", "s-12")
      error!(resp, -32602, "the move of G-score-unknown for \"make-the-self-check-green\"")
      Ev.note("observed: the move for a reason none of instrument-behavior-changed, wording-replaced or spec-grew is refused as -32602")
      unmoved!("G-score-unknown", "g-40")
    end)
  end

  # A golden whose scenario digest is not its scenario's is refused (WriteInstrumentGolden
  # requires 2). The issuer hands in "s-12" as the scenario's current digest and offers "s-11".
  def i005(w) do
    example(w, "only", fn _ ->
      case goldens() do
        [] -> Ev.note("given: the fresh store holds no golden")
        l -> fail!("the Given could not be established: the fresh store holds goldens #{Ev.short(l)}")
      end

      digests(%{@scenario => "s-12"})
      Ev.note("given: the instrument scenario #{inspect(@scenario)} has the scenario digest s-12")
      {_, resp} = write("G-score-unknown", @scenario, "s-11", "g-40")
      refused!(resp, "scenario-digest-mismatch", "the writing of G-score-unknown with the scenario digest s-11")
      absent!("G-score-unknown")
    end)
  end

  # A move to the same digest is refused (MoveInstrumentGolden requires 2; AMoveMovesSomething).
  def i006(w) do
    example(w, "only", fn _ ->
      given_golden("G-score-unknown", @scenario, "s-12", "g-40")
      resp = move("G-score-unknown", "g-40", "wording-replaced", false, "no change", "s-12")
      refused!(resp, "same-digest", "the move of G-score-unknown to its own digest g-40")
      unmoved!("G-score-unknown", "g-40")
    end)
  end

  # A move that names a stale scenario digest is refused (MoveInstrumentGolden requires 5).
  # G-score-unknown was written with the digest "g-40" (decisions.md "Spec round 2" 3) from its
  # scenario at "s-12"; the scenario has since been reworded, so the issuer now hands in "s-13"
  # as its current digest, and the move names the stale "s-12".
  def i007(w) do
    example(w, "only", fn _ ->
      given_golden("G-score-unknown", @scenario, "s-12", "g-40")
      digests(%{@scenario => "s-13"})
      Ev.note("given: G-score-unknown's scenario has been reworded; its scenario digest is now s-13")
      resp = move("G-score-unknown", "g-41", "wording-replaced", false, "reworded", "s-12")
      refused!(resp, "stale-scenario-digest", "the move of G-score-unknown naming the stale scenario digest s-12")
      unmoved!("G-score-unknown", "g-40")
      eq!(golden!("G-score-unknown")["scenario-digest"], "s-12", "G-score-unknown's scenario digest (the one it was written with)")
    end)
  end
end

defmodule Main do
  @items for n <- 1..7, do: String.pad_leading("#{n}", 3, "0")

  defp realdir(dir), do: dir |> then(&System.cmd("pwd", ["-P"], cd: &1)) |> elem(0) |> String.trim()

  def run([start]) do
    Process.put(:start, Path.expand(start))
    base = Path.join(realdir(System.tmp_dir!()), "grade-grader-lifecycle-#{System.unique_integer([:positive])}")
    File.mkdir_p!(base)

    verdicts =
      for n <- @items, reduce: [] do
        acc ->
          id = "03-" <> n
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
    IO.puts(:stderr, "usage: elixir grade_grader_lifecycle.exs START_SH")
    System.halt(2)
  end
end

Main.run(System.argv())
