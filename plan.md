# GDAL-in-Uploader Migration Plan

## Goal
Eliminate runtime dependence on Docker/Podman socket and external `gdal` container for data upload jobs.

Target outcome:
- `stack-data-uploader` performs vector/tabular/raster GDAL work locally inside its own container.
- Kubernetes upload jobs do not mount container runtime sockets.
- Behavior is reproducible from cold start using committed code/config only.

## Scope
Repos/folders involved:
- `stack/stack-clients`
- `stack/stack-data-uploader`
- `digital-twin/k8s`

Out of scope for first rollout:
- Refactoring non-uploader services that still use Docker exec pathways.
- Changing dataset semantics.

## Current Root Cause Snapshot
Observed in failed `power` upload jobs:
- Uploader can reach Docker API but query for stack-labeled `gdal` container returns empty list.
- Code then throws `NoSuchElementException: Cannot get container gdal`.

Key code locations:
- `stack-clients/.../gdal/GDALClient.java` uses `getContainerId("gdal")` in upload paths.
- `stack-clients/.../docker/DockerClient.java` throws if container not found.
- `digital-twin/k8s/scripts/run-upload-job.sh` injects Docker socket/env by default.

## Execution Principles
- Keep each change packet small and testable.
- Preserve backwards compatibility while introducing local mode.
- Prefer additive changes first; remove legacy socket path only after local mode passes.
- Every packet must include explicit verification commands and expected outputs.

## PostGIS Storage Standard (Decision)
Chosen standard pattern for Kubernetes and no-socket operation:
- One PostGIS container per stack namespace.
- One shared PostGIS database for uploader-managed datasets (default: `postgres`).
- One schema per dataset (schema name derived from dataset name, normalized to lowercase snake_case).
- One table per dataSubset inside that dataset schema.

Rationale:
- Ontop service instances are DB-bound (single DB URL per service), so a shared DB allows one Ontop service to serve all datasets.
- Schema-level separation gives clear ownership boundaries and avoids table-name collisions better than table-only naming.
- This pattern keeps operational complexity lower than per-dataset databases while preserving isolation.

Implication for Ontop:
- Yes, this removes the architectural need for multiple Ontop services in Kubernetes, as long as all Ontop-backed datasets use the shared database.
- Multiple mappings remain supported; they are merged and evaluated against the shared DB.

## Work Packets

### WP0 - Baseline + Evidence Freeze
Objective:
- Capture reproducible failing baseline before code changes.

Tasks:
1. Run `digital-twin/k8s/scripts/run-upload-job.sh power`.
2. Save latest job logs and exception snippet in PR description.
3. Record current image tag defaults in k8s scripts/CI.

Exit criteria:
- Baseline failure signature documented: `Cannot get container gdal`.

---

### WP1 - Introduce GDAL Execution Mode Abstraction
Objective:
- Add a strategy seam so uploader can run GDAL operations either via Docker exec (legacy) or local process execution (new).

Tasks:
1. Add execution mode config (`docker`, `local`) from env var, default `docker` initially.
2. Add `GdalCommandExecutor` abstraction for:
   - run command (with timeout, env)
   - mkdir/chmod/copy helpers where needed
   - file discovery currently done via `gdalmanage identify`
3. Implement `DockerGdalCommandExecutor` by adapting existing `ContainerClient` usage.
4. Implement `LocalGdalCommandExecutor` using `ProcessBuilder`.
5. Wire `GDALClient` to use the abstraction rather than direct `getContainerId("gdal")`.

Files expected (approx):
- `stack-clients/src/main/java/.../gdal/*` (new + edited)

Exit criteria:
- No functional change when mode is `docker`.
- Unit tests compile and pass in `stack-clients`.

---

### WP2 - Remove Remaining Container Exec Couplings in Uploader Path
Objective:
- Ensure uploader data paths do not require `postgis`/`gdal` container exec.

Tasks:
1. Replace `PostGISClient.addProjectionsToPostgis` container-psql path with JDBC SQL execution.
2. In raster upload flow, replace postgis-container `psql/raster2pgsql` exec with local commands in uploader container:
   - run `raster2pgsql ... | psql ...` locally
   - ensure DB connectivity uses endpoint config values.
3. Keep retry/error handling and logging parity.

Exit criteria:
- Uploader vector/tabular/raster flows execute without Docker API calls when mode is `local`.
- Existing Docker mode remains functional in legacy environments.

---

### WP3 - Build Runtime Image with Local GDAL Toolchain
Objective:
- Make `stack-data-uploader` self-sufficient at runtime.

Tasks:
1. Update `stack-data-uploader/Dockerfile` runtime stage to include:
   - GDAL CLI (`ogr2ogr`, `gdalmanage`, etc.)
   - PostGIS client tools (`raster2pgsql`, `psql`)
   - required native libs for Java GDAL bindings.
2. Add a startup sanity check command option (`--version` checks) or explicit startup logging.
3. Document image prerequisites in `stack-data-uploader/README.md`.

Exit criteria:
- Container starts and reports local GDAL tools available.
- No runtime dependency on external `gdal` container.

---

### WP4 - Switch K8s Path to Local Mode (Feature Flagged)
Objective:
- Use local mode in Kubernetes upload jobs while preserving controlled rollback.

Tasks:
1. In `digital-twin/k8s/scripts/run-upload-job.sh`:
   - set uploader env `STACK_GDAL_EXECUTION_MODE=local`
   - gate socket mount/env behind an opt-in legacy flag.
2. Align `digital-twin/k8s/scripts/data-uploader.yaml` with the same env and mount behavior.
3. Align CI defaults (`digital-twin/k8s/ci/pipeline.gitlab-ci.yml`) to use local mode image tag.

Exit criteria:
- Default K8s upload path does not mount `/var/run/docker.sock`.
- A documented rollback env flag can re-enable legacy behavior temporarily.

---

### WP5 - Version Alignment and Release Hygiene
Objective:
- Prevent image/code drift.

Tasks:
1. Align uploader image tag references with built artifact version strategy.
2. Add one source-of-truth variable for uploader version where feasible.
3. Update release notes/changelog section for migration.

Exit criteria:
- No hardcoded stale tag causing accidental old image usage.

---

### WP6 - Cold-Start Validation Matrix
Objective:
- Prove reproducibility end-to-end.

Test matrix:
1. Fresh cluster + empty relevant job state.
2. `power` synthetic upload.
3. Optional: `central` synthetic upload.
4. Validate expected GeoServer layers exist post-upload.
5. Confirm logs show no calls to Docker API for local mode.

Exit criteria:
- All matrix checks pass from clean start with committed config only.

---

### WP7 - Adopt Shared-DB + Schema-Per-Dataset in Stack-Clients
Objective:
- Make stack-clients default storage behavior align with the PostGIS standard above.

Tasks:
1. Introduce a dataset-level resolved schema default (normalized dataset name) when no subset schema is provided.
2. In Kubernetes mode, resolve dataset database to shared default (`postgres`) unless explicitly overridden by migration flags.
3. Remove broad database-level reset side effects from `PostgresDataSubset.loadInternal` and scope any reset/truncate behavior to the active schema/table only.
4. Ensure GDAL vector/raster upload paths consistently target resolved `database + schema + table`.
5. Add validation that prevents unsafe shared-DB collisions (duplicate schema/table targets across loaded datasets).
6. Keep legacy behavior available behind a compatibility flag during transition.

Exit criteria:
- Uploading multiple datasets to the same PostGIS database uses separate schemas by default and does not cross-impact other datasets.
- Kubernetes Ontop configuration succeeds with a single Ontop service for shared-DB datasets.
- No Docker socket requirement remains for Ontop service creation/configuration in Kubernetes uploader path.

## Rollback Plan
- Keep `STACK_GDAL_EXECUTION_MODE=docker` support until local mode is proven across environments.
- Keep socket mount path available only via explicit legacy flag during transition.
- If regressions appear, switch mode back to `docker` while retaining new code path for fixes.

## Agent Handoff Format
When finishing a packet, append a short update in this file under "Progress Log":
- Date/time (UTC)
- Packet ID
- Files changed
- Tests run + result
- Open risks/blockers

## Progress Log
- 2026-07-28: Plan initialized. Next action: WP1 scaffolding in `stack-clients`.
- 2026-07-28: WP1 started. Added GDAL execution mode scaffolding (`STACK_GDAL_EXECUTION_MODE` with `docker|local` parsing), wired into `GDALClient` startup logging, and verified compile with `mvn -DskipTests compile` in `stack-clients`.
- 2026-07-28: Branch split completed. Preserved Java-GDAL snapshot/tag changes on `op/java-gdal-client`, then continued migration on `op/gdal-binary-in-uploader`.
- 2026-07-28: Implemented first local execution slice for vector/tabular GDAL commands in `GDALClient` (local `ProcessBuilder` path + legacy docker fallback), and added GDAL/PostGIS CLI tooling to `stack-data-uploader` runtime image.
- 2026-07-28: Power upload advanced past GDAL and Ontop configuration but failed in DCAT generation when resolving `ontop-<stack>` endpoint config in K8s mode. Applied follow-up fix in `DCATUpdateQuery` to skip Ontop endpoint resolution for Kubernetes uploads.
- 2026-07-28: `power` upload passed end-to-end in K8s local mode. `central` then failed during large vector ingest because PostGIS backend was killed during GDAL COPY.
- 2026-07-28: Reworked `Ogr2OgrOptions` so `PG_USE_COPY` is runtime-configurable (`STACK_GDAL_PG_USE_COPY`) with default `YES` preserved for backward compatibility/tests. `run-upload-job.sh` now sets `STACK_GDAL_PG_USE_COPY=NO` by default in local mode.
- 2026-07-28: Added runtime-configurable ogr2ogr transaction grouping (`STACK_GDAL_OGR2OGR_GT`) and defaulted K8s local mode to `100` to reduce ingest pressure. Central upload still triggers PostGIS SIGKILL/recovery during flood vector load, so next packet needs PostGIS resource/runtime tuning in K8s config.
- 2026-07-28: Reviewed PostGIS write semantics in `DatasetLoader`, `PostgresDataSubset`, and GDAL upload paths. Current default is effectively one DB per dataset name in a shared PostGIS container, with subset-level schema support already present. Added formal storage decision and WP7 for shared DB + schema-per-dataset migration in stack-clients.
