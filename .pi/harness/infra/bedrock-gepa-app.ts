#!/usr/bin/env node
import { App } from "aws-cdk-lib";
import { BedrockGepaStack } from "./bedrock-gepa-stack.ts";

const app = new App();
const account = process.env.CDK_DEFAULT_ACCOUNT;
const region = process.env.CDK_DEFAULT_REGION ?? "eu-central-1";
const trustedPrincipalArn =
  app.node.tryGetContext("trustedPrincipalArn") ??
  process.env.PI_HARNESS_GEPA_TRUSTED_PRINCIPAL_ARN;
const modelId =
  app.node.tryGetContext("modelId") ??
  process.env.PI_HARNESS_GEPA_MODEL ??
  "eu.amazon.nova-2-lite-v1:0";

if (!account) throw new Error("CDK_DEFAULT_ACCOUNT is required; synth with --profile sandbox");
if (!trustedPrincipalArn) {
  throw new Error("trustedPrincipalArn context or PI_HARNESS_GEPA_TRUSTED_PRINCIPAL_ARN is required");
}

new BedrockGepaStack(app, "LoylyBedrockGepa", {
  env: { account, region },
  modelId,
  trustedPrincipalArn,
  description: "Least-privilege Bedrock inference access for Löyly ORC GEPA reflection",
});
