# CubeX Optional Integrations Design

## Goal

CubeX plugins remain independently installable. Optional APIs connect capabilities when two plugins
happen to be present; they do not turn either plugin into a required dependency of the other.

## Boundary

- The provider plugin owns domain state and its public service API. For example, Reputations owns
  `ReputationService` and the aggregated reputation data.
- A consumer keeps its standalone behavior and data. Contract continues to write
  `plugins/Contract/reputation.yml` whether Reputations is installed or not.
- `modules/cubex-integrations` is stateless connection code. It may discover a Bukkit service, but
  it must not own domain data, cache a provider across reloads, or define a cross-plugin god API.
- Consumers must not add another plugin project as `implementation` or `compileOnly`. A
  `softdepend` entry may be used only to request load ordering when the provider is present.

## Class-loader contract

Bukkit gives every plugin its own class loader. Shading the same service interface into a provider
and consumer produces two different Java `Class` identities, so `ServicesManager.load` cannot join
them. `OptionalServiceConnector` therefore:

1. finds the optional provider by plugin name;
2. loads the provider-owned API class through the provider plugin's class loader;
3. asks `ServicesManager` for that exact `Class` object;
4. returns a typed-neutral connection result and never caches it.

Domain adapters in the consumer use the provider's narrow public API reflectively. They must treat
missing, disabled, unregistered, or binary-incompatible providers as an unavailable optional
capability.

## Failure semantics

An optional observer or mirror is best-effort:

- the consumer commits its local mutation first;
- bridge absence or failure never rolls back or blocks the consumer operation;
- reconnection is attempted on later calls so provider reloads do not require a consumer reload;
- historical data is not silently replayed, because that could double-count values.

Financial or ownership transfer is different. A future Regions → Contract escrow bridge must use a
narrow, idempotent domain API with explicit operation IDs and recovery semantics. It must be designed
as a separate transactional integration, not layered onto the best-effort delta mirror.

## First adopter: Contract → Reputations

When Reputations is present, Contract registers four fields and mirrors new deltas:

- `Contract:completed`
- `Contract:cancelled`
- `Contract:expired`
- `Contract:disputed`

Contract remains fully functional without Reputations and retains its own commands, rendering, and
`reputation.yml`. Existing historical values are not imported automatically; a deliberate,
idempotent migration tool remains future work.
