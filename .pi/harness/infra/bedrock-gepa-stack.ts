import {
  CfnOutput,
  Duration,
  Stack,
  Tags,
  type StackProps,
} from "aws-cdk-lib";
import { ArnPrincipal, Effect, PolicyStatement, Role } from "aws-cdk-lib/aws-iam";
import type { Construct } from "constructs";

export type BedrockGepaStackProps = StackProps & {
  modelId: string;
  trustedPrincipalArn: string;
  destinationRegions?: readonly string[];
};

const defaultDestinationRegions = [
  "eu-north-1",
  "eu-west-3",
  "eu-south-1",
  "eu-south-2",
  "eu-west-1",
  "eu-central-1",
] as const;

export class BedrockGepaStack extends Stack {
  constructor(scope: Construct, id: string, props: BedrockGepaStackProps) {
    super(scope, id, props);
    const account = Stack.of(this).account;
    const region = Stack.of(this).region;
    const foundationModelId = props.modelId.replace(/^(?:eu|global)\./, "");
    const destinationRegions = props.destinationRegions ?? defaultDestinationRegions;

    if (!props.modelId.startsWith("eu.")) {
      throw new Error("GEPA Bedrock model must be an EU inference profile");
    }
    if (!props.trustedPrincipalArn.startsWith(`arn:aws:iam::${account}:role/`)) {
      throw new Error("trusted principal must be an IAM role in the deployment account");
    }

    const runtimeRole = new Role(this, "GepaRuntimeRole", {
      roleName: "loyly-gepa-bedrock-runtime",
      assumedBy: new ArnPrincipal(props.trustedPrincipalArn),
      description: "Least-privilege Bedrock runtime role for Löyly ORC GEPA reflection",
      maxSessionDuration: Duration.hours(4),
    });
    runtimeRole.addToPolicy(new PolicyStatement({
      effect: Effect.ALLOW,
      actions: ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
      resources: [
        `arn:aws:bedrock:${region}:${account}:inference-profile/${props.modelId}`,
        ...destinationRegions.map(
          (destinationRegion) =>
            `arn:aws:bedrock:${destinationRegion}::foundation-model/${foundationModelId}`,
        ),
      ],
    }));

    Tags.of(this).add("application", "loyly-pi-harness");
    Tags.of(this).add("component", "gepa-reflection");

    new CfnOutput(this, "GepaRuntimeRoleArn", { value: runtimeRole.roleArn });
    new CfnOutput(this, "GepaReflectionModel", { value: props.modelId });
    new CfnOutput(this, "GepaAwsRegion", { value: region });
  }
}
