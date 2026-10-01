# ProtocolObelisk cardinal policy

ProtocolObelisk is an adaptive compatibility service between a modded client and a vanilla lobby. Versions, fingerprints, modlists and embedded profiles are telemetry and enrichment inputs, never admission ACLs.

A well-formed client on a supported base protocol receives a real compatibility attempt. Unknown profiles use bounded capability-adaptive negotiation. Unverified registries, tags, configs and clientbound bootstraps are omitted rather than approximated. Serverbound channels are admitted only under negotiated flow/version/optionality, payload bounds, per-session ownership and rate limits.

Structural enrichment is never a routing or admission dependency. Missing, corrupt or incompatible translation evidence is quarantined immediately: it may withhold an optimization, but it cannot add a backend wait, change the selected server, suppress the backend's original packet, or reject an otherwise well-formed client.

The plugin may terminate a connection for malformed frames, impossible lengths, abuse, protocol-state corruption or other integrity/security violations. It must not terminate merely because the modpack, NeoForge build, fingerprint or profile is unknown.
