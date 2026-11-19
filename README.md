# discovergy-proxy

[![Maven and Docker build](https://github.com/IAmKonni/discovergy-proxy/actions/workflows/maven_and_docker.yml/badge.svg)](https://github.com/IAmKonni/discovergy-proxy/actions/workflows/maven_and_docker.yml)

This project is a Quarkus-based proxy for the `/last_reading` endpoint of the [inexogy API](https://api.inexogy.com/docs/) (formerly Discovergy). The proxy handles the OAuth 1.0a authorization and exposes the data on an endpoint without authorization, which is easier to integrate into tools and platforms such as Node-RED or Home Assistant.

## Installation / Setup

The REST endpoint is exposed on port 8080.

Example docker-compose file. Replace the environment variables with your personal inexogy credentials.

```docker-compose.yml
services:
  discovergy-proxy:
    image: iamkonni/discovergy-proxy:latest
    container_name: discovergy-proxy
    environment:
      - TZ=Europe/Berlin
      - DISCOVERGY_EMAIL=***your_mail_address***
      - DISCOVERGY_PASSWORD=***your_password***
      - DISCOVERGY_CLIENTID=***a_client_name_you_choose***
    restart: unless-stopped
    ports:
      - "8080:8080"
```

The previous variable names (`discovergy.email`, `discovergy.password`, `discovergy.clientid`) still work.

Optional environment variables:

| Variable | Default | Description |
|---|---|---|
| `DISCOVERGY_BASE_URL` | `https://api.inexogy.com/public/v1` | Base URL of the API |
| `DISCOVERGY_TIMEOUT` | `PT15S` | Timeout for API requests (ISO-8601 duration) |
| `DISCOVERGY_TOKEN_LIFETIME` | `PT24H` | After this time the OAuth tokens are renewed proactively |
| `QUARKUS_LOG_CATEGORY__ROCKS_KONRATH__LEVEL` | `INFO` | Set to `DEBUG` to log incoming requests |

Health and metrics endpoints: `/q/health`, `/q/health/live`, `/q/health/ready`, `/q/metrics`.

## Requesting the data

Just use HTTP GET requests to the following endpoint:

```endpoint
http(s)://host:8080/last_reading/<meterId>
```

The response of the API is passed through unchanged and looks like:

```application/json
{
  "time": 1643927694108,
  "values": {
    "energyOut": 126890677000000,
    "energy2": 0,
    "energy1": 70041149000000,
    "energyOut1": 126890677000000,
    "power": 564100,
    "energyOut2": 0,
    "power3": 200600,
    "power1": 173200,
    "energy": 70041149000000,
    "power2": 190300
  }
}
```

Error handling:

* invalid meter id → `400`
* client errors of the API (e.g. unknown meter `404`, rate limit `429`) are passed through
* API not reachable or server error → `502`

If the API rejects the tokens (`401`/`403`), the proxy logs in again automatically and retries once.

## Building

Requirements: Java 21 and Docker. Maven is provided via the wrapper.

```shell
# run tests and build the JVM application
./mvnw verify

# build the native executable inside a container (no local GraalVM needed)
./mvnw package -Dnative -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native-distroless -t discovergy-proxy .
```

The GitHub Action runs the tests on every push and pull request, builds the native image
and pushes it to Docker Hub on pushes to `master`. It needs the repository secrets
`DOCKER_USER` and `DOCKER_PASSWORD` (a Docker Hub access token).
