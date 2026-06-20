# ADR-005: Policy Attributes Key on Stable Id/Code, Never Display Text

| Field  |             Value             |
|--------|-------------------------------|
| Status | Accepted                      |
| Date   | 2026-06-17                    |
| Author | Ricardo Quintero Mármol Durán |

---

## Context

Authorization policies compare subject or resource attributes against expected values. The natural
human-readable name of an attribute (e.g. the department name "Human Resources") is mutable: it can
be renamed in the source catalog without any semantic change to the underlying thing it represents.

If policies key on display names, a routine rename in the HR catalog breaks every policy that
references that department — a silent security regression that only surfaces at enforcement time.

The same problem applies to person identifiers: using an internal auto-increment id ties policies to
the database row, which breaks when data is migrated or merged.

---

## Decision

All policy condition attributes use **stable, immutable identifiers** — never display text or
internal database surrogate keys.

Specifically:

- **Resource attributes** passed inline by the PEP in `ResourceRef.attributes` are keyed by the
  attribute's stable `id` or immutable `code` field from the source catalog (e.g. `departamento_id`,
  `tipo_permiso_code`). Human-readable labels are resolved separately for display only and are never
  stored in policies or decisions.
- **Person (subject) identity** uses **CURP** (Clave Única de Registro de Población) as the
  canonical identifier for any natural person in the Mexican government context. CURP is issued by
  the state, is immutable, and is stored as a standard user attribute in Keycloak from day one.
  It serves as the correlation key between systems and enables future unified-identity lookup without
  merging user stores (a link, not a merge; the merge is an IdP-layer concern, not a PDP concern).
- **Tax attributes** use **RFC** (Registro Federal de Contribuyentes) when the attribute is fiscal
  in nature.

The PEP is responsible for resolving display text to stable ids before sending the evaluation
request. The PDP never performs catalog lookups to translate names.

---

## Consequences

**Positive:**
- Renaming a department, permission type, or any other catalog entry has zero impact on existing
policies.
- Policy snapshots stored in audit logs remain interpretable without access to the current catalog
state.
- Testing is deterministic: fixture data uses stable codes rather than names that may vary by locale
or over time.

**Negative:**
- PEPs must know the stable id/code for each attribute they send. This requires the PEP to have
read access to the catalog's id/code fields, not just display names.
- Policy documents are less human-readable in isolation (a reviewer sees `departamento_id: "dept-42"`
instead of `departamento: "Human Resources"`). A policy UI layer should resolve codes to labels for
display.
