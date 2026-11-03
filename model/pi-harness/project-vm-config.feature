Feature: Conventional project-scoped VM and stack build configuration
  Project-specific Gondolin settings and target-stack Dockerfiles live under the repository
  root at harness/<project-id>/. The layout follows project and stack naming conventions,
  so no duplicate per-project harness profile JSON is required.

  Rule: A selected project resolves its own VM and target-stack build files by convention

    @item-01-050 @skip
    Scenario: Löyly resolves its Gondolin config and Dockerfile for each supported stack
      Given the selected project is loyly
      When the harness resolves its project runtime files
      Then its Gondolin VM config is harness/loyly/gondolin.json
      And its TypeScript, Rust, Java, and Rails Dockerfiles are harness/loyly/Dockerfile.<stack>
      And each selected target stack uses only its own Dockerfile

  Rule: Missing project runtime config fails closed

    @item-01-051 @skip
    Scenario: An unsupported or missing project stack Dockerfile is refused
      Given the selected project has no Dockerfile for the requested target stack
      When the harness resolves its project runtime files
      Then resolution fails before a Gondolin VM starts
      And no other project's Dockerfile is substituted
