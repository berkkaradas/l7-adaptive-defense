# L7 Adaptive Defense

This is my MSc thesis project at Swansea University.

The idea: normal firewalls only look at traffic from the outside, so they can miss slow attacks, and they can't tell users apart when many people share the same IP (like in an office). This project tries to fix that by also looking inside the microservices (failed logins, slow database calls, etc.) and reacting step by step instead of just blocking everything at once.

## Status

Just getting started. The basic project structure and the database/Kafka setup work. The actual logic (auth, risk scoring, gateway) is not written yet.

## What's in here

- `auth-service` - handles login, issues JWT tokens
- `orders-service` - a simple example service, just something to protect
- `gateway` - will sit in front of everything and decide what to do with each request (not built yet)
- `risk-engine` - will read events from Kafka and score how risky a user looks (not built yet)

## Stack

Java 21, Spring Boot, Spring Cloud Gateway, Kafka, PostgreSQL, Maven, Docker.

## Running it

```bash
docker compose up -d