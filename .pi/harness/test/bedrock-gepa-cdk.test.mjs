import assert from "node:assert/strict";
import test from "node:test";
import { App } from "aws-cdk-lib";
import { Match, Template } from "aws-cdk-lib/assertions";
import { BedrockGepaStack } from "../infra/bedrock-gepa-stack.ts";

const account = "826087505956";
const region = "eu-central-1";
const modelId = "eu.amazon.nova-2-lite-v1:0";
const trustedPrincipalArn =
  "arn:aws:iam::826087505956:role/aws-reserved/sso.amazonaws.com/eu-central-1/AWSReservedSSO_AdministratorAccess_90552a6c28dffc24";

test("CDK grants the GEPA runtime only the pinned Bedrock inference profile", () => {
  const app = new App();
  const stack = new BedrockGepaStack(app, "TestBedrockGepa", {
    env: { account, region },
    modelId,
    trustedPrincipalArn,
  });
  const template = Template.fromStack(stack);

  template.hasResourceProperties("AWS::IAM::Role", {
    AssumeRolePolicyDocument: {
      Statement: Match.arrayWith([
        Match.objectLike({
          Effect: "Allow",
          Principal: { AWS: trustedPrincipalArn },
          Action: "sts:AssumeRole",
        }),
      ]),
    },
  });
  const policies = template.findResources("AWS::IAM::Policy");
  const statement = Object.values(policies)[0].Properties.PolicyDocument.Statement[0];
  assert.deepEqual(statement.Action, [
    "bedrock:InvokeModel",
    "bedrock:InvokeModelWithResponseStream",
  ]);
  assert.deepEqual(new Set(statement.Resource), new Set([
    `arn:aws:bedrock:${region}:${account}:inference-profile/${modelId}`,
    "arn:aws:bedrock:eu-north-1::foundation-model/amazon.nova-2-lite-v1:0",
    "arn:aws:bedrock:eu-west-3::foundation-model/amazon.nova-2-lite-v1:0",
    "arn:aws:bedrock:eu-south-1::foundation-model/amazon.nova-2-lite-v1:0",
    "arn:aws:bedrock:eu-south-2::foundation-model/amazon.nova-2-lite-v1:0",
    "arn:aws:bedrock:eu-west-1::foundation-model/amazon.nova-2-lite-v1:0",
    "arn:aws:bedrock:eu-central-1::foundation-model/amazon.nova-2-lite-v1:0",
  ]));

  const outputs = template.findOutputs("*");
  assert.equal(outputs.GepaReflectionModel.Value, modelId);
  assert.equal(outputs.GepaAwsRegion.Value, region);
  assert.ok(outputs.GepaRuntimeRoleArn.Value);
});
