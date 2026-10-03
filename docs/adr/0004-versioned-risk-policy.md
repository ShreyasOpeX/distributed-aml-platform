# ADR 0004: Version Risk and Decision Policies

## Decision
Carry and persist rule, scoring and decision-policy versions with every adjudication.

## Why
Historical AML decisions must remain explainable after policy changes.

The versions are application governance metadata, not claims about regulatory approval. The reference versions are `rules-v1`, `score-v1`, and `policy-v1`.
