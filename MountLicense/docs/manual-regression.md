# Manual Regression Record

Use this file for real Spigot/Paper server checks that unit tests cannot
faithfully simulate. Do not mark a row passed until it has been exercised on a
running server with the built jar.

## Current Record

Date: 2026-05-22

Status: not yet run on a live server in this workspace. Automated checks cover
core pure logic only; Bukkit event and entity behavior still need runtime
evidence before a stable release claim.

## Checklist

| Area | Scenario | Expected result | Result |
| --- | --- | --- | --- |
| Startup | Install jar on clean Spigot/Paper profile | Plugin enables, default resources generate, `/ml help` works for a player with `mountlicense.use` | Not run |
| Permissions | Deny `mountlicense.use`, then run `/ml list`, `/ml info`, `/ml locate` | Commands are rejected with localized no-permission message | Not run |
| Registration | Register a tamed horse with a license | PDC/index record created, one license consumed, `/ml list` shows it | Not run |
| Registration | Try registering an unsaddled pig/strider, then add a saddle and retry | Unsaddled attempt is rejected; saddled attempt registers normally | Not run |
| Compatibility | On a newer server API, register supported newer rideables such as camel/camel husk, happy ghast, or nautilus | Profile loads without legacy errors and the entity registers/protects like other rideables | Not run |
| Plate UX | Register a vehicle, then run `/ml list`, `/ml info <plate>`, `/ml locate <plate>`, and bind a key | Plate looks like `ABC-123`, entity custom name is the plate, commands and key lore use the plate instead of a long UUID | Not run |
| Protection | Non-owner attempts mount, damage, inventory, and leash actions | Protected actions are cancelled; owner/trustee/admin semantics match README | Not run |
| Parking | Use `/ml park`, `/ml unpark`, `/ml lock`, `/ml unlock`, and auto-park | State changes persist and AI behavior matches config | Not run |
| Recall | Recall near loaded horse from safe ground | Entity teleports, state becomes ACTIVE, cooldown applies | Not run |
| Recall safety | Try recall while feet/head blocked, in liquid, on magma/lava/fire, and without solid ground below | Recall is refused and cooldown is cleared or not consumed for the unsafe attempt | Not run |
| Locate | Locate loaded and unloaded vehicles | Loaded flag and last-known coordinates are clear | Not run |
| Trust | Trust/untrust an online player and retry access as trustee | Trustee can use but cannot recall, release, or edit trust list | Not run |
| Persistence | Restart server after registration, trust, park, and locate updates | `vehicles.yml` reloads and `/ml list`/`info` retain data | Not run |
| Vault | Enable Vault economy with `economy.register_cost` | Successful registration charges once; insufficient funds fail without PDC/index writes; forced registration exception path refunds or logs refund failure | Not run |
| Reload | Delete one generated default file (`config.yml`, `vehicle-profiles.yml`, or `lang/en_US.yml`), then run `/ml admin reload` | Missing default file is recreated, language/config/profiles refresh, and index state is retained | Not run |
