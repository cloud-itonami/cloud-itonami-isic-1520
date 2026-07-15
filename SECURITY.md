# Security Policy

This project handles footwear plant, production-batch and crew-safety
coordination workflows. Treat vulnerabilities as potentially high impact even
when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real plant, batch or crew data exposure
- authorization bypass
- Footwear Plant Operations Governor bypass
- audit-ledger tampering
- over-disclosure in reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on plant/batch data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real plant/batch/crew data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
