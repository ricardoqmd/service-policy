# Docker & Docker Compose Guide

This document explains how to build and run Service Policy using Docker.
For general project info, see [README.md](README.md).

## TL;DR

```bash
# 1. (Optional) Customize ports/credentials
cp .env.example .env
# edit .env if you need different ports or credentials

# 2. Build the JVM jar
./mvnw clean package -DskipTests

# 3. Start everything (app + MongoDB + Mongo Express)
docker compose up --build

# 4. Access points (use ports from your .env if customized)
#    Service Policy:  http://localhost:8080
#    Swagger UI:      http://localhost:8080/q/swagger-ui
#    Mongo Express:   http://localhost:8081  (admin / admin)
```

## What's included

The `docker-compose.yml` defines a complete local stack:

|     Service      | Default port |              Purpose               |
|------------------|--------------|------------------------------------|
| `service-policy` | 8080         | The application itself             |
| `mongodb`        | 27017        | Policy storage (used from Phase 2) |
| `mongo-express`  | 8081         | Web UI to browse MongoDB data      |

All three run on an isolated bridge network. MongoDB data persists across
restarts via named volumes. Use `docker compose down -v` to wipe.

## Configurable ports via `.env`

All host-side ports are parameterizable to avoid conflicts when you already
have other services running locally. Container-internal ports stay constant.

```bash
# In your .env file:
SERVICE_POLICY_HOST_PORT=9090     # default: 8080
MONGO_DB_HOST_PORT=27018          # default: 27017
MONGO_EXPRESS_HOST_PORT=8090      # default: 8081
```

This is useful when:

- You have another MongoDB running on 27017 for another project.
- Port 8080 is taken by another service.
- You need to run two instances of this project in parallel (using two
  different `.env` files and `docker compose --env-file`).

Inside the containers, services always listen on their default ports
(8080 for Quarkus, 27017 for MongoDB, 8081 for Mongo Express). Only the
host-side mapping changes.

## Build flow: binary vs image vs container

Before walking through the Dockerfiles, it's worth being precise about three
concepts that are easy to conflate:

1. **Binary / artifact** — a file in `target/`. Not Docker.
2. **Docker image** — a template, created with `docker build`. Lives in your
   local Docker but isn't running anything.
3. **Docker container** — a live instance of an image, created with
   `docker run`. This is what actually executes your app.

For native builds, the flow is three commands:

```bash
# Step 1: produce the native binary.
# Quarkus uses a temporary Docker container with GraalVM internally,
# but the result is a binary in target/, NOT a Docker image.
./mvnw clean package -Pnative -Dquarkus.native.container-build=true

# Step 2: package the binary into a Docker image.
docker build -f src/main/docker/Dockerfile.native -t service-policy:native .

# Step 3: run the image as a container.
docker run --rm -p 8080:8080 service-policy:native
```

For JVM builds, the flow is similar:

```bash
./mvnw clean package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t service-policy:jvm .
docker run --rm -p 8080:8080 service-policy:jvm
```

Or use Docker Compose, which orchestrates everything:

```bash
docker compose up --build
```

## The four Dockerfiles

Service Policy ships with **four** Dockerfiles, each optimized for a different
deployment scenario. All live under `src/main/docker/`.

### Dockerfile.jvm — Standard JVM image (default)

The default choice. Runs on OpenJDK 21 with the Quarkus fast-jar layout.
This is what `docker-compose.yml` uses.

```bash
./mvnw clean package
docker build -f src/main/docker/Dockerfile.jvm -t service-policy:jvm .
docker run --rm -p 8080:8080 service-policy:jvm
```

**When to use:** development, traditional production, Kubernetes with
standard resource limits. The right choice for most cases.

- Image size: ~210 MB
- Startup: ~1 second
- Memory: ~150-250 MB

### Dockerfile.legacy-jar — Uber-jar for legacy tooling

Builds as a single fat-jar instead of the Quarkus fast-jar layout. Slightly
slower at runtime but compatible with tools that expect a traditional
`java -jar app.jar` workflow.

```bash
./mvnw clean package -Dquarkus.package.jar.type=uber-jar
docker build -f src/main/docker/Dockerfile.legacy-jar -t service-policy:legacy-jar .
```

**When to use:** integration with platforms or scripts that don't understand
the Quarkus fast-jar layout. Rarely needed.

### Dockerfile.native — Native executable (recommended for native builds)

Builds an Ahead-Of-Time compiled Linux binary using GraalVM. No JVM at
runtime. Faster cold start and smaller memory footprint than JVM.

```bash
./mvnw clean package -Pnative -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native -t service-policy:native .
docker run --rm -p 8080:8080 service-policy:native
```

The `container-build=true` flag means you don't need GraalVM installed locally
— Quarkus runs the build inside a container. The base image is
`registry.access.redhat.com/ubi9/ubi-minimal`, which ships with a modern
glibc that's compatible with the GraalVM builder out of the box.

**When to use:** serverless (AWS Lambda, Cloud Run), edge deployments, or
when you want to compare performance vs JVM. Build takes 3-5 minutes.

- Image size: ~125 MB
- Startup: ~30 milliseconds
- Memory: ~30-50 MB

### Dockerfile.native-micro — Native on minimal base (production optimization)

**Not the default**, kept as reference for advanced production scenarios.

Same native executable as `Dockerfile.native`, but on the smallest possible
base image (`quarkus-micro-image`). Trims ~75 MB off the final image at the
cost of requiring static linking against musl to avoid glibc version
mismatches.

For local development or portfolio projects, the extra 75 MB don't justify
the build complexity. The micro variant earns its place when:

- You're running hundreds or thousands of pods (registry storage adds up).
- You're deploying to edge devices with very limited storage.
- You're running cold-start-sensitive serverless functions.

If you do need it, the recipe is:

```bash
./mvnw clean package -Pnative \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.additional-build-args="--static --libc=musl"
docker build -f src/main/docker/Dockerfile.native-micro -t service-policy:native-micro .
```

Without `--static --libc=musl`, you'll likely see at runtime:

```
./application: /lib64/libc.so.6: version `GLIBC_2.33' not found
```

This happens because the GraalVM builder ships with a newer glibc than the
micro base image. Static linking against musl removes the glibc dependency
entirely, making the binary fully self-contained.

- Image size: ~50 MB total
- Startup: ~30 milliseconds
- Memory: ~30-50 MB

## Running native via docker-compose

There's an overlay file `docker-compose.native.yml` that swaps the JVM image
for the native one without rewriting the full compose:

```bash
# Build the native executable first
./mvnw clean package -Pnative -Dquarkus.native.container-build=true

# Run the stack using native
docker compose -f docker-compose.yml -f docker-compose.native.yml up --build
```

The MongoDB and Mongo Express services stay the same. Only the
`service-policy` service is overridden. Your `.env` port mappings still apply.

## Environment variables reference

Configuration follows 12-factor principles: everything is via env vars.

### Port mapping (host side)

|          Variable          | Default |         Description          |
|----------------------------|---------|------------------------------|
| `SERVICE_POLICY_HOST_PORT` | `8080`  | Host port for Service Policy |
| `MONGO_DB_HOST_PORT`       | `27017` | Host port for MongoDB        |
| `MONGO_EXPRESS_HOST_PORT`  | `8081`  | Host port for Mongo Express  |

### Credentials

|         Variable         |    Default     |        Description        |
|--------------------------|----------------|---------------------------|
| `MONGO_ROOT_USER`        | `root`         | MongoDB root user         |
| `MONGO_ROOT_PASSWORD`    | `rootpassword` | MongoDB root password     |
| `MONGO_EXPRESS_USER`     | `admin`        | Mongo Express UI user     |
| `MONGO_EXPRESS_PASSWORD` | `admin`        | Mongo Express UI password |

### Application configuration

|      Variable       | Default |              Description               |
|---------------------|---------|----------------------------------------|
| `QUARKUS_PROFILE`   | `dev`   | Active profile (`dev`, `test`, `prod`) |
| `QUARKUS_LOG_LEVEL` | `INFO`  | Root log level                         |

For Compose, copy `.env.example` to `.env` and edit values:

```bash
cp .env.example .env
# Edit .env with your preferences
docker compose up
```

## Resource sizing recommendations

For production deployments:

### JVM image

```yaml
resources:
  requests:
    memory: "256Mi"
    cpu: "100m"
  limits:
    memory: "512Mi"
    cpu: "1000m"
```

### Native image

```yaml
resources:
  requests:
    memory: "64Mi"
    cpu: "50m"
  limits:
    memory: "128Mi"
    cpu: "500m"
```

Native images can run with 4x less memory than JVM for the same workload.

## Building for multiple architectures

To produce multi-arch images (linux/amd64 + linux/arm64):

```bash
docker buildx create --use --name multiarch
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -f src/main/docker/Dockerfile.jvm \
  -t yourregistry/service-policy:latest \
  --push .
```

Useful for clusters mixing Intel and ARM nodes (e.g., AWS Graviton, Apple
Silicon developers).

## Troubleshooting

### "Cannot copy target/quarkus-app: no such file"

You forgot to run `./mvnw package` first. The Dockerfiles copy from `target/`.

### "Bind for 0.0.0.0:8080 failed: port is already allocated"

Another service is using port 8080 locally. Either:

- Stop the conflicting service, or
- Set `SERVICE_POLICY_HOST_PORT=9090` (or any free port) in your `.env`.

### Mongo Express shows "Could not connect to database"

MongoDB is still starting. Wait ~10 seconds or check `docker compose logs mongodb`.

### Port 27017 already in use

Another MongoDB is running locally. Either stop it or set
`MONGO_DB_HOST_PORT=27018` in your `.env`.

### "GLIBC_2.33 not found" or similar when running native-micro

The micro base image ships with an older glibc than the GraalVM builder.
The binary expects symbols the runtime image doesn't have.

Two ways to fix:

1. **Easy fix:** use `Dockerfile.native` instead of `Dockerfile.native-micro`.
   The UBI minimal base has a modern glibc and works out of the box.

2. **Production fix:** rebuild with static linking against musl, so the
   binary has no glibc dependency at all:

   ```bash
   ./mvnw clean package -Pnative \
     -Dquarkus.native.container-build=true \
     -Dquarkus.native.additional-build-args="--static --libc=musl"
   ```

See the `Dockerfile.native-micro` header comments for full context.

### Native build fails with "out of memory"

The GraalVM native build needs at least 4 GB RAM. Allocate more to Docker
Desktop: Settings → Resources → Memory.

### Container exits with "permission denied"

The Quarkus base images run as UID 185 (jvm) or 1001 (native). Check
that mounted volumes have correct permissions. Volumes you create via
named volumes (as in `docker-compose.yml`) work out of the box.

## Image comparison summary

|      Aspect      |      JVM      |  Legacy-jar  |       Native       |         Native-micro         |
|------------------|---------------|--------------|--------------------|------------------------------|
| Build time       | ~30s          | ~30s         | ~3-5 min           | ~3-5 min                     |
| Image size       | ~210 MB       | ~230 MB      | ~125 MB            | ~50 MB                       |
| Startup          | ~1s           | ~1.5s        | ~30ms              | ~30ms                        |
| RSS memory       | 150-250 MB    | 200-300 MB   | 30-50 MB           | 30-50 MB                     |
| Setup complexity | Trivial       | Trivial      | Trivial            | Needs musl static linking    |
| Use case         | Standard prod | Legacy tools | Cloud / serverless | Edge / high-density at scale |

**For most workloads, JVM is the right choice.** Reach for `native` when
cold start or memory density actually matters. Reach for `native-micro`
only at production scale where the extra 75 MB compound across hundreds
of pods.
