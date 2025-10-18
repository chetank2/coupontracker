# AI Editing Checklist

Use this quick checklist before submitting any PR that touches coupon extraction or supporting guardrails.

| Invariant | Verification method |
|-----------|---------------------|
| Guardrail rule acknowledged | Reference [COUPON_EXTRACTION_RULES.md](COUPON_EXTRACTION_RULES.md) and cite relevant rule numbers in the PR description. |
| Required fields preserved (`storeName`, `redeemCode`, `expiryDate`, `description`) | Run targeted unit/integration tests or provide before/after payload samples showing fields populated. |
| Expiry logic validated | Reproduce an example with relative and absolute dates, confirming screenshot timestamp usage in logs. |
| JSON schema unchanged | Execute serialization tests or paste the LLM response into the schema validator script. |
| Incident regression coverage updated | Add or point to regression fixtures/tests that cover any newly fixed scenario. |
| Observability alarms monitored | Capture recent log excerpts or dashboard screenshots showing no unresolved warnings. |

