# AutoShift Policy Report

Read-only Spring Boot + Thymeleaf application that reports which AutoShift policies are implemented by each cluster.

The application intentionally separates the two repositories that provide the inputs:

1. **Policy repository** — the AutoShift repository containing `policies/stable`, `policies/certified`, and `policies/community`.
2. **Site values repository** — the site-specific repository containing `autoshift/values/global.yaml`, `clustersets/*.yaml`, and `clusters/*.yaml`.

This matches the desired deployment model where policies come from the AutoShift mirror/policy branch while site-specific cluster and ClusterSet configuration remains in the site values repository.

## Configuration

```yaml
autoshift:
  report:
    policies:
      location: /path/to/autoshiftv2
      branch: main
    site-values:
      location: /path/to/site-values
      branch: main
    refresh-on-request: true
```

Each `location` may be either:

- a local filesystem path to an already checked-out Git repository; or
- a Git repository URL (`https://`, `http://`, `ssh://`, `git://`, or SCP-style `git@...`).

For URL locations the application uses Eclipse JGit to clone the repository and, when `refresh-on-request` is enabled, fetch/pull updates before generating a report. JGit is a pure-Java implementation of Git. The application does not require the `git` executable for URL repositories.

For private repositories, authentication should be supplied through the Git/SSH environment available to JGit or added as a dedicated credential configuration; credentials should not be embedded in `application.yaml`.

## Repository layouts

### Policy repository

```text
<policy-repo>/
└── policies/
    ├── stable/
    ├── certified/
    └── community/
```

Policies are discovered dynamically from these directories. The policy folder name is the policy identifier.

### Site values repository

```text
<site-values-repo>/
└── autoshift/
    └── values/
        ├── global.yaml
        ├── clustersets/
        │   └── *.yaml
        └── clusters/
            └── *.yaml
```

Files beginning with `_` are treated as examples and ignored.

## Values-file identity

Values files are configuration profiles. The same logical ClusterSet or cluster name may occur in multiple files, so names are not globally unique.

ClusterSet identity:

`<source-file>:<cluster-set-type>/<name>`

Cluster identity:

`<source-file>:<cluster-name>`

The application keeps the source file when resolving clusters, ClusterSets, and policy implementation. This prevents one sample/profile from silently overwriting another.

## Policy resolution

The policy catalog comes from the policy repository. Policy placement YAML is inspected for `autoshift.io/<label>` selectors. The site values repository supplies the labels and ClusterSet/cluster configuration used to determine whether the policy is enabled.

The effective value precedence is:

```text
cluster label
    ↓
ClusterSet label
    ↓
global/default context
```

`excludePolicies` is read from the **site values repository's** `autoshift/values/global.yaml`, and the policy folder name is used for the exclusion lookup.

## Run

```bash
mvn spring-boot:run
```

or:

```bash
java -jar target/autoshift-policy-report-0.1.0-SNAPSHOT.jar
```

The default `application.yaml` locations are placeholders and should be changed to your policy and site values repositories.

## Pages

- `/` — policy implementation matrix
- `/clusters` — clusters
- `/clusters/{source}/{name}` — cluster details
- `/clustersets` — ClusterSets
- `/clustersets/{type}/{source}/{name}` — ClusterSet details
- `/policies` — policy catalog
- `/policies/{name}` — policy implementation by cluster

## Important limitation

This is a source/configuration report, not a live ACM compliance report. It reports what the Git configuration indicates should be selected. It does not query an ACM hub for live `Policy`, `Placement`, or `ManagedCluster` status.
# autoshift-policy-report


## Private repositories

HTTP(S) Git repositories can be authenticated with an access token. Configure the token through the `token` property, preferably using an environment variable or Kubernetes/OpenShift Secret. The token is never included in repository display information or logs.

For OpenShift, the deployment expects an optional Secret named `autoshift-policy-report-repository-credentials` with keys `policies-token` and `site-values-token`. A template is provided at `deploy/repository-secret.example.yaml`. Create the real Secret separately, for example:

```bash
oc create secret generic autoshift-policy-report-repository-credentials \
  --from-literal=policies-token='YOUR_TOKEN' \
  --from-literal=site-values-token='YOUR_TOKEN'
```

The application uses a shared, thread-safe in-memory report cache. Multiple users can read the application concurrently without each request cloning or parsing the repositories. When a cached report exists, an expired cache is refreshed in the background so users continue to receive the last successful report during a refresh. The first load still waits for the initial report to be built.
