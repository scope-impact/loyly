Feature: Bootstrap the Pi harness runtime
  The repository-root harness Makefile prepares all local Pi harness and project runtime
  images and seals a fresh harness snapshot from the committed harness revision. It does
  not build the Löyly application, require an oracle, publish artifacts, or start a cycle.

  Rule: The Makefile builds and verifies all configured Gondolin runtimes without oracles

    @item-01-047 @skip
    Scenario: make bootstrap builds every harness and supported-stack runtime
      Given the repository checkout and its harness runtime configurations
      And no product oracle or instrument oracle is available
      When the operator runs make -C harness bootstrap
      Then npm dependencies are installed in .pi/harness
      And Docker builds TypeScript, Rust, Java, Rails, cut, instrument, and grader images
      And Gondolin builds the corresponding runtime assets under .pi/harness/.state/assets
      And every generated runtime asset is verified
      And bootstrap records one manifest binding the harness revision, runtime assets, calibration fixtures, and generic conformance machinery
      And carried and uncarried harness calibration passes before that manifest is sealed
      And no oracle reference or regeneration state is changed

  Rule: Harness bootstrap does not build or grade the product

    @item-01-048 @skip
    Scenario: make bootstrap does not run Löyly application or oracle workflows
      Given the operator runs make -C harness bootstrap
      When bootstrap completes
      Then it has not built or modified the Löyly implementation
      And it has not authored a cycle oracle or resolved or published a product oracle
      And it has not submitted a regeneration cycle

  Rule: Bootstrap failure is surfaced to the operator

    @item-01-049 @skip
    Scenario: An invalid runtime build fails the Makefile target
      Given Docker or Gondolin cannot build or verify a configured runtime
      When the operator runs make -C harness bootstrap
      Then make exits unsuccessfully with the failing command visible
      And no successful bootstrap is reported
