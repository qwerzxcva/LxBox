# RSXM — Rust eXtensible Micro-kernel

An experimental answer to three questions about AIBox's future core, and a
working prototype instead of only a design document.

## The three questions

### 1. Can sing-box's Go core be Rust, gradually?

Yes — and this branch shows the safe shape of "gradually".

Two facts bound the answer:

* **Protocol reality.** The Rust proxy ecosystem's most mature kernel
  (meow-rs) speaks ss/trojan/anytls, not vless/reality. A big-bang rewrite
  would strand every node the app supports while it plays catch-up on a
  protocol family it never implemented.
* **Byte-for-byte equivalence is testable.** Routing decisions, DNS
  answers and connection accounting can be diffed against the Go core on
  real configs. That is what makes incremental replacement safe: replace
  one module, replay traffic, compare.

The staged plan this branch prototypes:

| stage | module | verification |
|-------|--------|--------------|
| 1 | **rules** (this branch) | replay route decisions vs sing-box on the same rule set |
| 2 | stats / connection table | diff counters under a traffic generator |
| 3 | dns (cache + resolution order) | diff answers + cache hit counts |
| 4 | dialer (vless / ss handshake) | interop test against real servers |
| 5 | tun packet engine | throughput + energy benchmark (tun-bench) |

Each stage is a drop-in `Module` behind the scheduler; the Go core keeps
serving whatever is not yet replaced.

### 2. Why not split the core into micro-kernels?

For *this* product, in-process modules — not separate kernels — are the
right decomposition. A separate kernel per function would put IPC on the
packet fast path, and AIBox's existing split (UI process vs `:vpn`
process) already isolates the only failure that must not take the tunnel
down. Inside the `:vpn` process, the useful split is by **failure domain
and hot path**, which is what RSXM's module set encodes:

```
tun      — packet I/O        (HEV/lwIP engine; crash = tunnel down)
rules    — routing table     (pure; crash = misroute, restartable)
dialer   — protocol handshakes
dns      — resolution + cache
stats    — counters + connections
```

### 3. HEV as the packet engine — without SOCKS

Worth doing, and the prototype's `rsxm-tun` crate sketches it: HEV (lwIP
plus its task scheduler) reads the tun fd with its batched/GSO-capable
paths and exposes *parsed flows*; the scheduler's router answers them
directly. The current AIBox integration instead pipes tun -> SOCKS ->
sing-box, which adds a stack and a per-flow local handshake for no
benefit. Removing the SOCKS hop is the single cleanest win available to
the current design.

What this branch deliberately does NOT do: vendor HEV's C sources into a
second build graph. That lands in the runtime crate once the rules stage
has proven out.

## What is actually built here

```
rust/rsxm/
  crates/rsxm-rules   rule engine: phases + dedupe + domain trie (15 tests)
                      + check module: replay a compiled sing-box rule table
                      and explain the routing decision (12 tests)
  crates/rsxm-core    scheduler + Module contract + routing table (5 tests)
  crates/rsxm-dns     DNS micro-kernel: fake-IP pool, optimistic cache,
                      raced upstreams (9 tests)
  crates/rsxm-tun     packet-engine module skeleton + config rendering (2 tests)
  crates/rsxm-agent   CLI: boot the scheduler, dry-run a route, replay a
                      compiled table (--check)
```

The route-check engine is wired into the app through `aibox-core`'s JNI
surface (`AiboxCore.routeCheck`): the Routes screen's 防分流检测 section
compiles the current state with the same `ConfigCompiler.compile` the VPN
process uses, sends the rule table plus the query over, and renders the
explained decision — the micro-kernel's first user-visible capability, and
the foundation for stage-1 verification (replay route decisions against
the Go kernel on identical inputs).

Verified in this branch:

* `cargo test` — 15 tests green;
* end-to-end CLI check — `api.example.com` folds into the covering suffix
  rule, `www.google.co.jp` hits the keyword rule, a package-scoped rule
  pre-empts both;
* cross-compile to `aarch64-linux-android` — 347 KB binary;
* CI runs the suite plus the cross-compile on every push to `rsxm-dev`.

## Licensing note (read before merging code from other projects)

AIBox's core lineage is GPL-3.0 (sing-box fork). When absorbing ideas
from other kernels:

* **mihomo** — GPL-3.0: compatible, but any copied code makes the whole
  binary GPL (already true).
* **Xray-core** — MPL-2.0: file-level copyleft; copied files must keep
  their MPL notices and stay MPL. Prefer re-implementation from behaviour,
  not copy-paste.
* **meow-rs** — MIT: permissive; fine to reuse with attribution.

The rules engine here is written from scratch against documented
semantics, so none of the constraints bind yet.
