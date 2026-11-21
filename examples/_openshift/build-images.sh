#!/usr/bin/env bash
#
# build-images.sh — build the five data-mesh service images inside OpenShift
# Local.
#
# Maven packages each service on the host (the fast-jar in
# target/quarkus-app); the quarkus-openshift extension (the `openshift`
# Maven profile in each service pom) then uploads it to a binary S2I build
# on the cluster. The build runs on ubi10/openjdk-25 and pushes ImageStream
# tag <service>:$IMAGE_TAG into the project. No container engine on the
# host, no exposed registry, and the cluster never pulls from Maven Central.
#
#   ./openshift/build-images.sh                  # every service in lib.sh
#   ./openshift/build-images.sh orders            # just one
#
# Adapted from the lgtm-crc skill template: the skill's build-images.sh runs
# one reactor build (`mvn -pl "$modules" -am package`) because its reference
# project has a root pom aggregating every service module. This book ships
# one standalone Maven project per chapter example (examples/13-*,
# examples/14-*, the two examples/15-*/ submodules, examples/17-*) with no
# root reactor spanning them, so each service is built in its own directory
# (lib.sh's MODULE_DIR), after installing the shared domain-model once (every
# module depends on com.patterncatalyst.agentapi:domain-model:1.0.0, exactly
# as `mvn -B verify` requires it installed first for the other runtimes).
#
# Each service pom needs:
#   <profile><id>openshift</id><dependencies><dependency>
#     <groupId>io.quarkus</groupId><artifactId>quarkus-openshift</artifactId>
#   </dependency></dependencies></profile>
# (quarkus-container-image-openshift alone generates no BuildConfig.)
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Newest UBI with the newest JDK, exact tag. Re-check:
#   skopeo list-tags docker://registry.access.redhat.com/ubi10/openjdk-25
# Fallback when ubi10 lacks the runtime: ubi9/openjdk-25 (same JDK).
BASE_JVM_IMAGE="${BASE_JVM_IMAGE:-registry.access.redhat.com/ubi10/openjdk-25:1.24-15}"

require_crc
oc get project "$NS" >/dev/null 2>&1 || fail "project $NS missing: run ./openshift/install-infra.sh first"
command -v mvn >/dev/null 2>&1 || fail "mvn not on PATH"

targets=("$@")
(( ${#targets[@]} )) || targets=("${SERVICES[@]}")
for svc in "${targets[@]}"; do
    [[ -n "${MODULE_DIR[$svc]+x}" ]] || fail "unknown service $svc (not in lib.sh's MODULE_DIR)"
done

step "Installing the shared domain model (examples/domain-model)"
[[ -d "$DOMAIN_MODEL_DIR" ]] || fail "no domain-model at $DOMAIN_MODEL_DIR"
mvn -B -q -ntp -f "$DOMAIN_MODEL_DIR/pom.xml" install -DskipTests \
    || fail "mvn install failed for domain-model (mvn -f $DOMAIN_MODEL_DIR/pom.xml install -DskipTests)"
ok "domain-model installed"

step "Building ${#targets[@]} image(s) in-cluster: ${targets[*]}"
# quarkus.openshift.name forces the BuildConfig and ImageStream name to the
# chart's service key: the quarkus-openshift extension names those after
# quarkus.openshift.name (default: the Maven artifactId), NOT after
# quarkus.container-image.name. Every module's artifactId differs from its
# data-mesh product name (e.g. module 15's mcp-facade is deployed here as
# payments-facade), so without the override the ImageStream (artifactId)
# would not match what the Helm chart looks up. quarkus.container-image.name
# is set to the same value for the pushed image coordinates.
# quarkus.openshift.version sets the BuildConfig's output
# tag; with quarkus.container-image.tag alone it stays at the project
# version (e.g. 1.0.0-SNAPSHOT). kubernetes.deploy=false: build and push
# only, the Helm chart owns the Deployments. quarkus.openshift.namespace is
# the one that selects the target project: Quarkus 3.40 ignores
# quarkus.kubernetes-client.namespace and KUBERNETES_NAMESPACE for the
# openshift build, and `crc start` resets the context namespace to `default`.
for svc in "${targets[@]}"; do
    dir="$MAVEN_DIR/${MODULE_DIR[$svc]}"
    [[ -d "$dir" ]] || fail "no module directory for $svc: $dir"
    step "  $svc ($dir)"
    ( cd "$dir" && mvn -B -q -ntp package -DskipTests -Popenshift \
        -Dquarkus.container-image.build=true \
        -Dquarkus.container-image.name="$svc" \
        -Dquarkus.openshift.name="$svc" \
        -Dquarkus.container-image.tag="$IMAGE_TAG" \
        -Dquarkus.openshift.version="$IMAGE_TAG" \
        -Dquarkus.openshift.base-jvm-image="$BASE_JVM_IMAGE" \
        -Dquarkus.openshift.namespace="$NS" \
        -Dquarkus.kubernetes.deploy=false ) \
        || fail "Maven/S2I build failed for $svc (oc get builds -n $NS; oc logs build/$svc-<n> -n $NS)"
done

step "Verifying ImageStream tags"
for svc in "${targets[@]}"; do
    ref="$(oc get istag "$svc:$IMAGE_TAG" -n "$NS" -o jsonpath='{.image.dockerImageReference}' 2>/dev/null)" \
        || fail "no ImageStream tag $svc:$IMAGE_TAG"
    ok "$svc:$IMAGE_TAG ${ref##*@}"
done
