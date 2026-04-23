<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-6.md at e68200267aa0430aff2c5ddbb93c4d8edb9bb48e 2026-04-23 -->
---
id: DEC-6
domain: architecture
level: architectural
title: "Asymmetric-key registration for compute clients and info-service uploads"
status: active
created_by: bootstrap
created_at: 2026-04-11
last_updated_by: bootstrap
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - security
  - authentication
  - distributed-compute
related_to: [DEC-4]
---

# DEC-6 — Asymmetric-key client registration

## Context
Two integration points accept network traffic from untrusted or semi-trusted parties:
1. The slot-optimization service receives result packets from volunteer compute clients.
2. The participant info service receives tournament uploads from Tournament Manager instances it does not own.

Both are abuse vectors for fake or replayed payloads.

## Decision
Both integration points use asymmetric-key registration:
- The client (compute node, or Tournament Manager instance) generates a keypair at registration and submits the public key to the service.
- All subsequent submissions are signed by the client and verified by the service against the registered public key.
- The slot-optimization service additionally logs every result with source IP and timestamp; the **first valid result wins**, and any later results for the same job are logged but ignored.
- Result-submission timeouts are enforced.

## Impact
- Both services need a key registry and signature-verification middleware.
- Client implementations (compute node, Tournament Manager) need keypair generation, secure local storage, and request signing.
- Audit logs must retain source IP, timestamp, and signature outcome for every accepted and rejected request.
