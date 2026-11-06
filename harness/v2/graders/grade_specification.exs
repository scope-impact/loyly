# grade_specification.exs START_SH: the sealed grader of spec v2 slice 01, the specification
# context (.pi/spec/specification/spec.feature, @item-01-001 to @item-01-015).
#
# Authored from the selected Gherkin, the public seam (.pi/spec/specification/seam.md, with
# the slice's event model and Allium for its params, results and refusals) and the public
# calibration protocol (AGENTS.md, "The instrument") only. Its author read no candidate,
# transcript, prior verdict or host credential.
#
# For each item it builds fresh state in a temporary directory (the spec files the scenario's
# Given describes and a fresh LOYLY_GRAIN_STORE file), starts the candidate's start.sh there,
# and drives it only over its JSON-RPC seam, as the operator would: the operator reads the
# spec file to say what is unstamped (the contract ScenarioReading, implemented here a second
# time from its invariants) and reads it again for the identity written into it, which is
# public behaviour. A Given that says a slice "has stamped" items is established through the
# same seam, by stamping, and checked before the When.
#
# Output: a JSON list on stdout, one {"id", "verdict", "evidence"} per item it reached, in
# item order, the shape harness/score.clj files. An item it could not reach (start.sh did not
# start, or exited or went silent before answering anything) gets no verdict, and is named on
# stderr: score.clj records it as unreached and failed, and never drops it. Exit 0 whenever
# the grading ran; exit 2 on a usage error. Elixir 1.18 or later (its JSON module); no deps.

defmodule Reading do
  # contract ScenarioReading (.pi/spec/specification/allium/context.allium)
  @heading ~r/^\s*(?:Scenario|Scenario Outline):(.*)$/
  @item ~r/@item-\d+-\d+/

  def read(text) do
    {_, scenarios, empty} =
      text
      |> String.split("\n")
      |> Enum.with_index(1)
      |> Enum.reduce({[], [], []}, fn {raw, n}, {run, scen, empty} ->
        line = String.trim_trailing(raw, "\r")

        cond do
          String.starts_with?(String.trim(line), "@") ->
            {run ++ [line], scen, empty}

          m = Regex.run(@heading, line) ->
            [_, rest] = m

            if rest == "",
              do: {[], scen, empty ++ [n]},
              else: {[], scen ++ [%{line: n, title: title(rest), tag: tag(run)}], empty}

          true ->
            {[], scen, empty}
        end
      end)

    %{scenarios: scenarios, empty: empty}
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
      all -> all |> List.last() |> hd()
    end
  end

  def unstamped(text),
    do: for(s <- read(text).scenarios, s.tag == nil, do: %{"line" => s.line, "title" => s.title})

  def item_tag_line?(line), do: Regex.match?(~r/^\s*@item-\d+-\d+\s*$/, line)

  # The tag lines directly above the line at index i (0-based) of lines.
  def run_above(lines, i) do
    lines
    |> Enum.take(i)
    |> Enum.reverse()
    |> Enum.take_while(&String.starts_with?(String.trim(&1), "@"))
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
  def short(v), do: v |> JSON.encode!() |> String.slice(0, 400)
end

defmodule Op do
  # What the operator does and what the grader checks, each step noted as evidence.
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

  def stamp(slice, source, unstamped, empty) do
    params = %{"slice" => slice, "source" => source, "unstamped" => unstamped, "empty-title-lines" => empty}
    resp = Seam.call("specification/stamp-slice", params)
    offered = Enum.map_join(unstamped, ", ", &"#{&1["line"]} #{inspect(&1["title"])}")

    Ev.note(
      "sent stamp-slice slice #{slice} source #{rel(source)} unstamped [#{offered}] empty-title-lines #{JSON.encode!(empty)}" <>
        " -> " <> answered(resp)
    )

    resp
  end

  # Stamp as the operator does: offer what the file holds, read with ScenarioReading.
  def stamp_as_read(slice, source) do
    text = File.read!(source)
    stamp(slice, source, Reading.unstamped(text), Reading.read(text).empty)
  end

  # Establish a Given "slice S has stamped ..." through the seam, and check it.
  def given_stamped(slice, source, ids) do
    case stamp_as_read(slice, source) do
      {:ok, r} ->
        got = r |> stamped() |> Enum.map(& &1["item-id"])
        if got != ids, do: fail!("the Given could not be established: stamping handed out #{inspect(got)}, not #{inspect(ids)}")

      {:error, _} ->
        fail!("the Given could not be established: the setup stamping was refused")
    end
  end

  def answered({:ok, r}), do: "result #{Ev.short(r)}"
  def answered({:error, e}), do: "error #{Ev.short(e)}"

  def rel(path), do: path |> String.split("/plan/") |> List.last() |> then(&("plan/" <> &1))

  def ok!({:ok, r}, _what) when is_map(r), do: r
  def ok!({:ok, r}, what), do: fail!("#{what} answered a result that is not an object: #{Ev.short(r)}")
  def ok!({:error, _}, what), do: fail!("#{what} was refused")

  def refused!({:ok, _}, kind, what), do: fail!("#{what} succeeded; expected the refusal #{kind}")

  def refused!({:error, e}, kind, what) do
    code = is_map(e) && e["code"]
    got = is_map(e) && is_map(e["data"]) && e["data"]["refusal"]
    if code != -32000, do: fail!("#{what}: error code #{inspect(code)}, not the refusal code -32000")
    if got != kind, do: fail!("#{what}: refusal #{inspect(got)}, not #{inspect(kind)}")
    Ev.note("observed: #{what} refused as #{kind}")
    to_string(e["message"])
  end

  def events(r) do
    case r["events"] do
      l when is_list(l) -> l
      other -> fail!("the result's events are #{Ev.short(other)}, not a list")
    end
  end

  def stamped(r),
    do: r |> events() |> Enum.filter(&(is_map(&1) and &1["type"] == "specification/scenario-stamped"))

  def expect_stamped!(r, slice, want) do
    got = for e <- stamped(r), do: {e["item-id"], e["line"], e["title"], e["slice"]}
    want = for {id, line, title} <- want, do: {id, line, title, slice}

    if got != want,
      do: fail!("scenario-stamped events (item-id, line, title, slice) were #{inspect(got)}, expected #{inspect(want)}")

    Ev.note("observed: scenario-stamped events #{inspect(Enum.map(want, &elem(&1, 0)))} as expected")
  end

  def no_stamped!(r) do
    case stamped(r) do
      [] -> Ev.note("observed: success with no scenario-stamped event (fully stamped)")
      s -> fail!("expected nothing stamped; observed #{Ev.short(s)}")
    end
  end

  # The item tag the file now carries for the scenario titled title (ScenarioReading).
  def expect_tag!(source, title, tag) do
    case Enum.filter(Reading.read(File.read!(source)).scenarios, &(&1.title == title)) do
      [s] ->
        if s.tag != tag, do: fail!("in the file, #{inspect(title)} carries #{inspect(s.tag)}, not #{tag}")
        Ev.note("observed: the file carries #{tag} on #{inspect(title)}")

      l ->
        fail!("the file holds #{length(l)} scenarios titled #{inspect(title)}, not one")
    end
  end

  # Every line of before is kept, and the only lines added are inserted item tags.
  def kept!(before, source, inserted) do
    after_ = File.read!(source)
    strip = fn t -> t |> String.split("\n") |> Enum.reject(&Reading.item_tag_line?/1) end
    count = fn t -> t |> String.split("\n") |> Enum.count(&Reading.item_tag_line?/1) end
    if strip.(after_) != strip.(before), do: fail!("the file's other lines were not all kept as they were")
    added = count.(after_) - count.(before)
    if added != inserted, do: fail!("#{added} item-tag lines were added to the file, not #{inserted}")
    Ev.note("observed: every other line kept, #{inserted} item-tag line(s) added")
  end

  def unchanged!(source, before) do
    if File.read!(source) != before, do: fail!("the spec file changed")
    Ev.note("observed: the spec file is unchanged")
  end

  # No item tag directly above the (single) line equal to heading.
  def untagged_line!(source, heading) do
    lines = source |> File.read!() |> String.split("\n")

    case Enum.filter(Enum.with_index(lines), fn {l, _} -> l == heading end) do
      [{_, i}] ->
        run = Reading.run_above(lines, i)
        if Reading.tag(run) != nil, do: fail!("#{inspect(heading)} carries #{Reading.tag(run)}")
        Ev.note("observed: #{inspect(String.trim(heading))} carries no item tag")

      l ->
        fail!("the file holds #{length(l)} lines #{inspect(heading)}, not one")
    end
  end

  def ledger(slice) do
    resp = Seam.call("specification/stamped-items", %{"slice" => slice})
    Ev.note("sent stamped-items slice #{slice} -> " <> answered(resp))
    r = ok!(resp, "stamped-items")

    case r["slices"] do
      l when is_list(l) -> Enum.find(l, &(is_map(&1) and &1["slice"] == slice))
      other -> fail!("stamped-items answered slices #{Ev.short(other)}, not a list")
    end
  end

  def ids(entry), do: for(i <- entry["items"] || [], do: i["item-id"])

  def next!(entry, slice, n) do
    got = entry && entry["next-number"]
    if got != n, do: fail!("slice #{slice}'s next number is #{inspect(got)}, not #{n}")
    Ev.note("observed: slice #{slice} hands out @item-#{slice}-#{String.pad_leading("#{n}", 3, "0")} next")
  end

  def no_items!(entry, slice) do
    if entry && ids(entry) != [], do: fail!("slice #{slice} has stamped items #{inspect(ids(entry))}")
    Ev.note("observed: slice #{slice} has no stamped item")
  end
end

defmodule Items do
  import Op

  @feature ["Feature: the gate", "  The gate's scenarios.", "", "  Rule: a fresh boot", ""]
  @steps ["    Given a candidate", "    When the gate boots it", "    Then it answers"]

  # A spec file of unstamped scenarios with these titles, headings on lines 6, 10, 14, ...
  defp plain(work, titles) do
    put(work, "plan/03-gate/spec.feature", @feature ++ Enum.flat_map(titles, &(["  Scenario: #{&1}"] ++ @steps)))
  end

  def example(work, name, fun) do
    dir = Path.join(work, name)
    File.mkdir_p!(dir)
    Ev.note("example #{name}")

    try do
      Seam.start(Process.get(:start), dir)
      fun.(dir)
    after
      Seam.stop()
    end
  end

  def i001(w) do
    example(w, "only", fn w ->
      src =
        put(w, "plan/03-gate/spec.feature",
          @feature ++ ["  Scenario: boots fresh"] ++ @steps ++ ["", "  # the seam", "  Scenario: refuses a busy seam"] ++ @steps)

      at!(src, 6, "  Scenario: boots fresh")
      at!(src, 12, "  Scenario: refuses a busy seam")
      before = File.read!(src)
      r = ok!(stamp("03", src, [%{"line" => 6, "title" => "boots fresh"}, %{"line" => 12, "title" => "refuses a busy seam"}], []), "stamping")
      expect_stamped!(r, "03", [{"03-001", 6, "boots fresh"}, {"03-002", 12, "refuses a busy seam"}])
      expect_tag!(src, "boots fresh", "@item-03-001")
      expect_tag!(src, "refuses a busy seam", "@item-03-002")
      kept!(before, src, 2)
      next!(ledger("03"), "03", 3)
    end)
  end

  def i002(w) do
    example(w, "only", fn w ->
      src = put(w, "plan/03-gate/spec.feature", ["Feature: the gate", "  Scenario: boots fresh"] ++ @steps)
      notes = put(w, "plan/notes/spec.feature", ["Feature: notes", "  Scenario: a note"] ++ @steps)
      before = File.read!(src)
      notes_before = File.read!(notes)
      r = ok!(stamp_as_read("03", src), "stamping slice 03")
      expect_stamped!(r, "03", [{"03-001", 2, "boots fresh"}])
      expect_tag!(src, "boots fresh", "@item-03-001")
      kept!(before, src, 1)

      case stamp("notes", notes, Reading.unstamped(notes_before), []) do
        {:ok, _} -> fail!("stamping plan/notes as a slice succeeded")
        {:error, _} -> Ev.note("observed: plan/notes refused as a slice")
      end

      unchanged!(notes, notes_before)
      resp = Seam.call("specification/stamped-items", %{})
      Ev.note("sent stamped-items for every slice -> " <> answered(resp))
      slices = ok!(resp, "stamped-items")["slices"]
      names = if is_list(slices), do: Enum.map(slices, &(is_map(&1) && &1["slice"])), else: slices
      if names != ["03"], do: fail!("the stamped slices are #{inspect(names)}, not [\"03\"]")
      Ev.note("observed: the only stamped slice is 03")
    end)
  end

  def i003(w) do
    example(w, "only", fn w ->
      src = plain(w, ["boots fresh", "refuses a busy seam", "reports its boot", "times out"])
      given_stamped("03", src, ["03-001", "03-002", "03-003", "03-004"])

      src =
        put(w, "plan/03-gate/spec.feature",
          @feature ++
            ["  @item-03-001", "  Scenario: boots fresh"] ++ @steps ++
            ["  @item-03-002", "  Scenario: refuses a busy seam"] ++ @steps ++
            ["  @item-03-003", "  Scenario: reports its boot"] ++ @steps ++
            ["", "  Scenario: refuses a second launch"] ++ @steps)

      at!(src, 22, "  Scenario: refuses a second launch")
      before = File.read!(src)
      r = ok!(stamp("03", src, [%{"line" => 22, "title" => "refuses a second launch"}], []), "stamping")
      expect_stamped!(r, "03", [{"03-005", 22, "refuses a second launch"}])
      expect_tag!(src, "refuses a second launch", "@item-03-005")
      expect_tag!(src, "boots fresh", "@item-03-001")
      expect_tag!(src, "refuses a busy seam", "@item-03-002")
      expect_tag!(src, "reports its boot", "@item-03-003")
      kept!(before, src, 1)
      entry = ledger("03")
      got = for i <- (entry && entry["items"]) || [], do: {i["item-id"], i["title"]}

      for want <- [{"03-001", "boots fresh"}, {"03-002", "refuses a busy seam"}, {"03-003", "reports its boot"}, {"03-005", "refuses a second launch"}],
          want not in got,
          do: fail!("the stamped items #{inspect(got)} do not hold #{inspect(want)}")

      Ev.note("observed: no stamped item renumbered")
      next!(entry, "03", 6)
    end)
  end

  def i004(w) do
    example(w, "only", fn w ->
      src = plain(w, ["boots fresh", "refuses a busy seam"])
      given_stamped("03", src, ["03-001", "03-002"])
      before = File.read!(src)
      if Reading.unstamped(before) != [], do: fail!("the Given could not be established: the file still holds unstamped scenarios")
      r = ok!(stamp("03", src, [], []), "the second stamping")
      no_stamped!(r)
      unchanged!(src, before)
      entry = ledger("03")
      if ids(entry || %{}) != ["03-001", "03-002"], do: fail!("the stamped items are #{inspect(ids(entry || %{}))}")
      tags = for s <- Reading.read(before).scenarios, do: s.tag
      if tags != ["@item-03-001", "@item-03-002"], do: fail!("the file's item tags are #{inspect(tags)}")
      Ev.note("observed: each item identity names exactly one stamped item")
      next!(entry, "03", 3)
    end)
  end

  def i005(w) do
    example(w, "only", fn w ->
      src = plain(w, ["boots fresh"])
      given_stamped("03", src, ["03-001"])
      src = put(w, "plan/03-gate/spec.feature", @feature ++ ["  @item-03-001", "  Scenario: boots fresh from its launch entrypoint"] ++ @steps)
      before = File.read!(src)
      r = ok!(stamp("03", src, [], []), "stamping")
      no_stamped!(r)
      unchanged!(src, before)
      expect_tag!(src, "boots fresh from its launch entrypoint", "@item-03-001")
      next!(ledger("03"), "03", 2)
    end)
  end

  def i006(w) do
    example(w, "only", fn w ->
      File.mkdir_p!(Path.join(w, "plan"))
      src = Path.join(w, "plan/07-score/spec.feature")
      msg = refused!(stamp("07", src, [], []), "no-spec-found", "stamping slice 07")
      if not String.contains?(msg, "plan/07-score/spec.feature"), do: fail!("the refusal does not name plan/07-score/spec.feature: #{inspect(msg)}")
      Ev.note("observed: the refusal names plan/07-score/spec.feature")
      no_items!(ledger("07"), "07")
    end)
  end

  def i007(w) do
    example(w, "only", fn w ->
      src = plain(w, ["boots fresh", "refuses a busy seam"])
      given_stamped("03", src, ["03-001", "03-002"])
      File.rm!(src)
      Ev.note("removed plan/03-gate/spec.feature")
      msg = refused!(stamp("03", src, [], []), "no-spec-found", "stamping slice 03")
      if not String.contains?(msg, "plan/03-gate/spec.feature"), do: fail!("the refusal does not name plan/03-gate/spec.feature: #{inspect(msg)}")
      if String.contains?(String.downcase(msg), "fully stamped"), do: fail!("the refusal says fully stamped: #{inspect(msg)}")
      Ev.note("observed: the refusal names plan/03-gate/spec.feature and does not say fully stamped")
      next!(ledger("03"), "03", 3)
    end)
  end

  defp ab(w, tail) do
    src = put(w, "plan/03-gate/spec.feature", @feature ++ ["  Scenario: a"] ++ @steps ++ ["", "", "  Scenario: b"] ++ @steps ++ tail)
    at!(src, 6, "  Scenario: a")
    at!(src, 12, "  Scenario: b")
    src
  end

  def i008(w) do
    example(w, "never-stamped", fn w ->
      src = ab(w, [])
      before = File.read!(src)
      refused!(stamp("03", src, [%{"line" => 12, "title" => "b"}, %{"line" => 6, "title" => "a"}], []), "unstamped-mismatch", "offering b before a")
      unchanged!(src, before)
      no_items!(ledger("03"), "03")
    end)

    example(w, "stamped-boots-fresh", fn w ->
      src = plain(w, ["boots fresh"])
      given_stamped("03", src, ["03-001"])
      src = ab(w, ["", "  @item-03-001", "  Scenario: boots fresh"] ++ @steps)
      at!(src, 18, "  Scenario: boots fresh")
      before = File.read!(src)
      offer = [%{"line" => 18, "title" => "boots fresh"}, %{"line" => 6, "title" => "a"}, %{"line" => 12, "title" => "b"}]
      refused!(stamp("03", src, offer, []), "unstamped-mismatch", "offering boots fresh, a and b")
      unchanged!(src, before)
      entry = ledger("03")
      if ids(entry || %{}) != ["03-001"], do: fail!("slice 03's stamped items are #{inspect(ids(entry || %{}))}")
      next!(entry, "03", 2)
    end)
  end

  def i009(w) do
    example(w, "only", fn w ->
      src = put(w, "plan/03-gate/spec.feature", @feature ++ ["  Scenario: boots fresh", "    Given a candidate", "", "  Scenario:"] ++ @steps)
      at!(src, 6, "  Scenario: boots fresh")
      at!(src, 9, "  Scenario:")
      before = File.read!(src)
      r = ok!(stamp("03", src, [%{"line" => 6, "title" => "boots fresh"}], [9]), "stamping")
      expect_stamped!(r, "03", [{"03-001", 6, "boots fresh"}])
      expect_tag!(src, "boots fresh", "@item-03-001")
      untagged_line!(src, "  Scenario:")
      kept!(before, src, 1)

      reported =
        for e <- events(r), is_map(e), e["type"] == "specification/empty-title-headings-reported", do: e

      case reported do
        [e] ->
          if e["lines"] != [9], do: fail!("the empty-title report names lines #{inspect(e["lines"])}, not [9]")

          if not String.contains?(to_string(e["source"]), "plan/03-gate/spec.feature"),
            do: fail!("the empty-title report names source #{inspect(e["source"])}")

          Ev.note("observed: an empty-title heading reported in plan/03-gate/spec.feature on line 9")

        l ->
          fail!("#{length(l)} empty-title-headings-reported events, not one")
      end
    end)
  end

  defp empty_title(w, tail) do
    src = put(w, "plan/03-gate/spec.feature", @feature ++ ["  Scenario: boots fresh", "    Given a candidate", "", "  Scenario:"] ++ @steps ++ tail)
    at!(src, 6, "  Scenario: boots fresh")
    at!(src, 9, "  Scenario:")
    src
  end

  def i010(w) do
    example(w, "never-stamped", fn w ->
      src = empty_title(w, [])
      before = File.read!(src)
      refused!(stamp("03", src, [%{"line" => 6, "title" => "boots fresh"}], []), "empty-title-mismatch", "reporting no empty-title heading")
      unchanged!(src, before)
      no_items!(ledger("03"), "03")
    end)

    example(w, "stamped-two", fn w ->
      src = plain(w, ["first stamped", "second stamped"])
      given_stamped("03", src, ["03-001", "03-002"])
      src = empty_title(w, ["", "  @item-03-001", "  Scenario: first stamped"] ++ @steps ++ ["  @item-03-002", "  Scenario: second stamped"] ++ @steps)
      before = File.read!(src)
      refused!(stamp("03", src, [%{"line" => 6, "title" => "boots fresh"}], []), "empty-title-mismatch", "reporting no empty-title heading")
      unchanged!(src, before)
      entry = ledger("03")
      if ids(entry || %{}) != ["03-001", "03-002"], do: fail!("slice 03's stamped items are #{inspect(ids(entry || %{}))}")
      next!(entry, "03", 3)
    end)
  end

  def i011(w) do
    example(w, "only", fn w ->
      src =
        put(w, "plan/03-gate/spec.feature",
          @feature ++
            ["  Scenario Outline: an outline", "    Given <who>", "    When the gate boots it", "    Then it answers", "",
             "    Examples:", "      | who |", "      | a candidate |", "  Scenario Template: a template"] ++ @steps)

      at!(src, 6, "  Scenario Outline: an outline")
      at!(src, 14, "  Scenario Template: a template")
      before = File.read!(src)
      r = ok!(stamp("03", src, [%{"line" => 6, "title" => "an outline"}], []), "stamping")
      expect_stamped!(r, "03", [{"03-001", 6, "an outline"}])
      expect_tag!(src, "an outline", "@item-03-001")
      untagged_line!(src, "  Scenario Template: a template")
      kept!(before, src, 1)
    end)
  end

  def i012(w) do
    example(w, "only", fn w ->
      src = put(w, "plan/03-gate/spec.feature", @feature ++ ["  Scenario:   "] ++ @steps)
      at!(src, 6, "  Scenario:   ")
      before = File.read!(src)
      r = ok!(stamp("03", src, [%{"line" => 6, "title" => " "}], []), "stamping")
      expect_stamped!(r, "03", [{"03-001", 6, " "}])
      expect_tag!(src, " ", "@item-03-001")
      kept!(before, src, 1)
      entry = ledger("03")
      got = for i <- (entry && entry["items"]) || [], do: {i["item-id"], i["title"]}
      if got != [{"03-001", " "}], do: fail!("slice 03's stamped items are #{inspect(got)}")
      Ev.note("observed: @item-03-001 has the title \" \"")
    end)
  end

  def i013(w) do
    example(w, "only", fn w ->
      src = put(w, "plan/03-gate/spec.feature", @feature ++ ["    Scenario:   a padded title   "] ++ @steps)
      at!(src, 6, "    Scenario:   a padded title   ")
      before = File.read!(src)
      r = ok!(stamp("03", src, [%{"line" => 6, "title" => "a padded title"}], []), "stamping")
      expect_stamped!(r, "03", [{"03-001", 6, "a padded title"}])
      expect_tag!(src, "a padded title", "@item-03-001")
      kept!(before, src, 1)
      entry = ledger("03")
      got = for i <- (entry && entry["items"]) || [], do: {i["item-id"], i["title"]}
      if got != [{"03-001", "a padded title"}], do: fail!("slice 03's stamped items are #{inspect(got)}")
      Ev.note("observed: @item-03-001 has the title \"a padded title\"")
    end)
  end

  def i014(w) do
    example(w, "only", fn w ->
      src = plain(w, ["drifted away"])
      given_stamped("03", src, ["03-001"])
      src = put(w, "plan/03-gate/spec.feature", @feature ++ ["  @item-03-001", "", "  Scenario: drifted away"] ++ @steps)
      at!(src, 8, "  Scenario: drifted away")
      before = File.read!(src)
      r = ok!(stamp("03", src, [%{"line" => 8, "title" => "drifted away"}], []), "stamping")
      expect_stamped!(r, "03", [{"03-002", 8, "drifted away"}])
      expect_tag!(src, "drifted away", "@item-03-002")
      kept!(before, src, 1)
      next!(ledger("03"), "03", 3)
    end)
  end

  def i015(w) do
    example(w, "only", fn w ->
      src = put(w, "plan/03-gate/spec.feature", @feature |> List.replace_at(4, "  @wip @slow") |> Kernel.++(["  Scenario: has tags but no item tag"] ++ @steps))
      at!(src, 5, "  @wip @slow")
      at!(src, 6, "  Scenario: has tags but no item tag")
      before = File.read!(src)
      r = ok!(stamp("03", src, [%{"line" => 6, "title" => "has tags but no item tag"}], []), "stamping")
      expect_stamped!(r, "03", [{"03-001", 6, "has tags but no item tag"}])
      expect_tag!(src, "has tags but no item tag", "@item-03-001")
      kept!(before, src, 1)
      lines = src |> File.read!() |> String.split("\n")
      i = Enum.find_index(lines, &(&1 == "  Scenario: has tags but no item tag"))
      tokens = lines |> Reading.run_above(i) |> Enum.flat_map(&String.split/1)
      if not ("@wip" in tokens and "@slow" in tokens), do: fail!("the tags above the heading are #{inspect(tokens)}: @wip and @slow were not kept")
      Ev.note("observed: @wip and @slow kept above the heading")
    end)
  end
end

defmodule Main do
  @items ~w(001 002 003 004 005 006 007 008 009 010 011 012 013 014 015)

  defp realdir(dir), do: dir |> then(&System.cmd("pwd", ["-P"], cd: &1)) |> elem(0) |> String.trim()

  def run([start]) do
    Process.put(:start, Path.expand(start))
    base = Path.join(realdir(System.tmp_dir!()), "grade-specification-#{System.unique_integer([:positive])}")
    File.mkdir_p!(base)

    verdicts =
      for n <- @items, reduce: [] do
        acc ->
          id = "01-" <> n
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
    IO.puts(:stderr, "usage: elixir grade_specification.exs START_SH")
    System.halt(2)
  end
end

Main.run(System.argv())
