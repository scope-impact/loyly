# Bedrock GEPA infrastructure

`LoylyBedrockGepa` creates the least-privilege runtime role used by ORC's GEPA
reflection model. The role can invoke only the pinned EU inference profile and
its declared regional foundation-model destinations.

Authenticate and deploy from `.pi/harness`:

```bash
aws sso login --profile sandbox
npx cdk deploy --profile sandbox --require-approval never \
  -c trustedPrincipalArn="$(aws iam list-roles --profile sandbox \
    --query 'Roles[?contains(RoleName, `AWSReservedSSO_AdministratorAccess`)].Arn | [0]' \
    --output text)"
```

Configure a local role profile from the stack output:

```bash
aws configure set role_arn arn:aws:iam::826087505956:role/loyly-gepa-bedrock-runtime \
  --profile loyly-gepa-sandbox
aws configure set source_profile sandbox --profile loyly-gepa-sandbox
aws configure set region eu-central-1 --profile loyly-gepa-sandbox
```

Run ORC with the derived profile and the stack outputs:

```bash
AWS_REGION=eu-central-1 \
PI_HARNESS_GEPA_MODEL=eu.amazon.nova-2-lite-v1:0 \
node .pi/harness/scripts/with-aws-profile.mjs \
  --profile loyly-gepa-sandbox -- .pi/harness/orc-worker/run.sh
```

No AWS access key, secret, or session token belongs in repository state, ORC
events, prompts, or Pi transcripts. The wrapper resolves the short-lived role
session into the ORC child process only because the Cognitect credential chain
used by the Bedrock adapter does not resolve AWS SSO profiles directly.
