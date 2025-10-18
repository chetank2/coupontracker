## Summary
- [ ] Explain the change clearly and why it is necessary.

## Guardrails Checklist
- [ ] Ran `python scripts/check_ai_invariants.py`
- [ ] CTA stop-word diff reviewed and approved by guardrail owner
- [ ] Validator, fuzz, and golden tests all pass locally
- [ ] Confirmed provenance metadata is preserved for new field candidates
- [ ] Documented any prompt template updates in release notes

## Mini-ADR (200-400 characters)
Provide a concise decision record covering the problem, chosen approach, and risk mitigations. Keep it between 200 and 400 characters.

## Testing
- [ ] Added or updated automated tests as needed
- [ ] Manually verified critical flows when applicable
