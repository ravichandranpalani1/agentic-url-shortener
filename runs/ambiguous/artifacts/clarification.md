# Requirement Clarification

**Chosen interpretation:** Defend the analytics executor submission against RejectedExecutionException so a saturated/shutting-down executor degrades to a logged drop instead of throwing on the request thread, while keeping analytics fully asynchronous.

**Rejected interpretation:** Record analytics synchronously before responding, guaranteeing no click is ever lost at the cost of coupling redirect latency to the analytics write path.

See this stage's APPROVED entry in the audit log / decision lineage for who decided this and why.
