# Security Policy

## Supported Versions

Service Policy is under active development. Until version 1.0 is released, only the
latest version receives security updates.

| Version |     Supported      |
|---------|--------------------|
| 0.x     | :white_check_mark: |

## Reporting a Vulnerability

We take the security of Service Policy seriously. If you discover a security
vulnerability, please **do not open a public issue**.

Instead, report it privately using one of these channels:

1. **GitHub Security Advisories** (preferred):
   Go to https://github.com/ricardoqmd/service-policy/security/advisories/new
   and submit a private security advisory.

2. **Email**: Open a private contact via the GitHub profile at
   https://github.com/ricardoqmd

When reporting, please include:

- A description of the vulnerability.
- Steps to reproduce.
- Affected version(s).
- Potential impact.
- Suggested fix (if any).

We commit to:

- Acknowledge receipt within 5 business days.
- Provide an initial assessment within 10 business days.
- Coordinate disclosure timing with you.
- Credit you in the security advisory (unless you prefer to remain anonymous).

## Scope

This security policy covers:

- The Service Policy core engine.
- The HTTP API surface.
- Default configurations shipped with the project.

It does NOT cover:

- Vulnerabilities in dependencies (please report those upstream).
- Misconfigurations in your own deployment (your responsibility).
- Issues in policies you write yourself (your responsibility).

## Security Best Practices for Operators

If you deploy Service Policy in your environment, you are responsible for:

- Keeping the service updated to the latest patch version.
- Securing the admin endpoints (`/admin/*`) — they should only be reachable
  from your control plane, never from the internet.
- Rotating credentials regularly.
- Enabling TLS for all production traffic.
- Configuring your reverse proxy / API gateway to enforce authentication.
- Monitoring audit logs for anomalous decisions.
- Following the principle of least privilege when configuring policies.

For deployment guidance, see the documentation in `docs/`.
