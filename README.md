# discovergy-proxy

This project is a quarkus based implementation for accessing the `\last_reading` endpoint of the [Discovergy API](https://api.discovergy.com/docs/) without OAuth 1.0 authorization. The proxy services encapsulates the OAuth autorization and exposes the data to an unauthorized endpoint, which can be integrated more easily in different tools and platforms, for example Node-RED.

If for accessing the you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .

## Installation / Setup

The REST endpoint is exposed on port 8080.

This is am example docker-compose file that can be used. Please replace the environment variables to your personal Discovergy credentials.

```docker-compose.yml
version: "3.7"

services:
  discovergy-proxy:
    image: iamkonni/discovergy-proxy:latest
    container_name: discovergy-proxy
    environment:
      - TZ=Europe/Berlin
      - discovergy.password=***your_discovergy_password***
      - discovergy.email=***your_mail_address***
      - discovergy.clientid=***a_client_name_you_have_to_choose***
    restart: unless-stopped
    ports:
      - "8080:8080"

```

## Requesting the data

Just use HTTP GET requests to the following endpoint:

```endpoint
http(s)://host:8080/last_reading/<meterId>
```

  
The response should look like:

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
