# Using Cardano Client Bindings with AI Agents

Cardano Client Bindings is designed to be **AI-friendly**: the API surface is small and identical across all four languages, transactions are plain YAML, and everything an agent needs fits in one document. Point your AI tool at the artifacts below and it can write correct code against the bindings without any training data about them.

## TL;DR

Point your AI agent at the **[AI Starter Pack](../starter-pack/)** or the full docs dump:

| File | When to use it |
|---|---|
| **[`/ai/starter-pack.md`](../starter-pack/)** | The single highest-leverage artifact. Distills the offline contract, the API groups, TxPlan YAML with the intent catalog, error codes, signing roles, and the known limitations agents trip over. |
| **[`/llms.txt`](https://bloxbean.github.io/cardano-client-bindings/llms.txt)** | Curated index of this docsite, following [llmstxt.org](https://llmstxt.org/). Small, agent-friendly. |
| **[`/llms-full.txt`](https://bloxbean.github.io/cardano-client-bindings/llms-full.txt)** | The full docsite concatenated as a single markdown file, for full-coverage ingestion. |

## Per-tool setup

### Claude Code (CLI)

Drop the starter pack into your project as `CLAUDE.md` (or append it to an existing one):

```bash
curl -o CLAUDE.md https://bloxbean.github.io/cardano-client-bindings/ai/starter-pack.md
```

Claude Code reads `CLAUDE.md` at the start of every session, so the agent always has the bindings' contract in context. For multi-project setups, reference the hosted version from your global `~/.claude/CLAUDE.md`:

```markdown
When working with cardano-client-lib bindings (Python/Go/Rust/JS `ccl` packages),
follow https://bloxbean.github.io/cardano-client-bindings/ai/starter-pack/
```

### Cursor

Add a project rule:

```bash
mkdir -p .cursor/rules
curl -o .cursor/rules/ccl-bindings.mdc https://bloxbean.github.io/cardano-client-bindings/ai/starter-pack.md
```

### Continue (VS Code / JetBrains)

Add a URL context provider in `.continue/config.json`:

```json
{
  "contextProviders": [
    { "name": "url", "params": { "url": "https://bloxbean.github.io/cardano-client-bindings/llms-full.txt" } }
  ]
}
```

### ChatGPT / Claude.ai / other chat UIs

Paste the starter pack (or attach it as a file) at the start of the conversation, then ask for what you need — e.g. *"Using cardano-client-lib for Python as described above, build and sign a stake-delegation transaction."*

## What agents get wrong without context

These are the failure modes the starter pack exists to prevent:

1. **Inventing an online API** — the library never fetches or submits; chain data is an input, submission is your job.
2. **Calling the broken functions** — `tx.from_json`, `tx.sign_with_secret_key`, and `plutus.data_to_json`/`data_from_json` fail in the current release (GraalVM reflection gaps).
3. **Signing with the wrong keys** — certificates need `sign_tx_with_keys` with explicit roles, or the node rejects the transaction.
4. **Confusing `Network` values with on-chain network ids** — they're inverted for mainnet.
5. **Guessing TxPlan field names** — the intent catalog in the starter pack has the verified YAML shapes.
6. **Using Node.js for the JS wrapper** — it's Bun-only.
