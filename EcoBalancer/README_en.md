# EcoBalancer: A Smart Minecraft Economy Plugin

English | [简体中文](README.md)

EcoBalancer is a smart Minecraft economy plugin that optimizes your server's economy through an intelligent tax system for inactive players. It promotes fair competition, creates an active gaming environment, and provides smart management solutions for your server's economy.

## Key Features

- Automated and configurable tax settings
- Tax revenue saved into a public account
- Wealth distribution histogram
- Basic statistics like mean and standard deviation for player balance

![Imgur](https://i.imgur.com/0eXcPeO.gif)

![Imgur](https://imgur.com/L7wagZ9.gif)

## Features

- Automated routine taxing (daily/weekly/monthly) of inactive player accounts
- Customizable deduction rates based on balance classes and activity levels
- Manual taxation commands for on-demand adjustments

**Note**: \
EcoBalancer is currently undergoing testing. We encourage rigorous evaluation before deployment. For bug reports or suggestions, please open an issue.
Also, make sure you remove the old config & language files when updating.

**Prerequisite**: Vault

## Commands

All commands require the `/ecobal` prefix (or alias `/eb`):

### Basic Commands
- `/ecobal help`: Display help information
- `/ecobal reload`: Reload the configuration file
- `/ecobal checkall [filters...]`: Update target players' balances as per configuration settings
- `/ecobal checkplayer <player>`: Update a specific offline player's balance as per configuration settings
- `/ecobal stats [bars] [low] [up]`: Show descriptive statistics and wealth distribution histogram
- `/ecobal interval <low> <up> [page]`: List players' balance in a specific interval
- `/ecobal perc <balance> [low] [up]`: Show percentile of a specific balance among players
- `/ecobal gui`: Open GUI dashboard/policy menu
- `/ecobal migrate <check|run|backup>`: Check/run migration and create backups
- `/ecobal tax ...`: Manage tax config and policy execution
- `/ecobal tax status`: Show the current tax run status
- `/ecobal tax fund`: Show ledger balance, lifetime collected tax, and latest tax run
- `/ecobal tax stats [player]`: Show a player's latest and lifetime tax paid
- `/ecobal policy <list|set|execute>`: Alias routed to tax policy management

### Economic Analysis Commands
- `/ecobal gini [days]`: Calculate Gini Coefficient (measure wealth inequality), optional parameter to filter players active within N days
- `/ecobal concentration [percentages...]`: Wealth concentration analysis, show percentage of wealth held by Top N% players (default: 1%, 5%, 10%, 20%)
- `/ecobal report [operation_id]`: View tax operation report, showing total tax collected, players affected, tax bracket distribution, etc.
- `/ecobal health [filters...]`: Economy health score report
- `/ecobal impact [operation_id]`: Tax impact report for one operation
- `/ecobal trends [days]`: Time-series trend report based on snapshots

### Record Management Commands
- `/ecobal checkrecords [page]`: Show all operation records
- `/ecobal checkrecord <operation_id> [sort] [page]`: Show details of a specific operation
- `/ecobal restore <operation_id>`: Restore a specific operation

**Alias**: You can use `/eb` instead of `/ecobal`, e.g., `/eb gini` is equivalent to `/ecobal gini`

## Permissions

Core:

- `ecobalancer.command.ecobal`: Main command root
- `ecobalancer.command.*`: Per-subcommand permissions (`checkall`, `checkplayer`, `stats`, `interval`, `perc`, `checkrecords`, `checkrecord`, `restore`, `gini`, `concentration`, `report`, `health`, `impact`, `trends`, `tax`, `migrate`, `reload`)
- `ecobalancer.gui.view`: Open GUI dashboard
- `ecobalancer.gui.admin`: Manage policies in GUI / execute policy
- `ecobalancer.admin`: Admin notifications and migration operations
- `ecobalancer.exempt`: Tax exemption node (if enabled by config)
- `ecobalancer.exempt.policy.<policy>`: Exempt a player from one policy
- `ecobalancer.exempt.operation.<operation>`: Exempt a player from one operation type, such as `checkall`, `checkplayer`, or `policy`

## Configuration (config.yml)

```yaml
language: 'en_US' # Language en_US/zh_CN
info-on-login: true # Show user info on login
record-retention-days: 30 # Record retention days
check-time: "20:00" # Time format is HH:mm
check-schedule:
 type: 'weekly' # Options: 'daily', 'weekly', 'monthly'
 days-of-week: [2, 4, 6] # Monday, Wednesday, Friday (7 = Saturday, 1 = Sunday)
 dates-of-month: [1] # 1st day of each month
deduct-based-on-time: true
# The following two options only take effect when deduct-based-on-time is true
inactive-days-to-deduct: 50 # Days inactive before starting deductions
inactive-days-to-clear: 500 # Days inactive before clearing balance
# Tax brackets for deductions
tax-brackets:
 - threshold: 100000
   rate: 0.001 # Tax rate
 - threshold: 1000000
   rate: 0.01 # Tax rate
 - threshold: null # No limit
   rate: 0.02 # Tax rate
tax-account: true # Whether to use tax account
tax-account-name: 'tax' # Tax account name
tax-exempt:
  enabled: true
  global-permission: 'ecobalancer.exempt'
  policy-permission-prefix: 'ecobalancer.exempt.policy'
  operation-permission-prefix: 'ecobalancer.exempt.operation'
debt-mode: 'skip' # skip, drain, allow-negative
debt-commands:
  - 'broadcast &e%player% &cdoes not have enough money to pay taxes.'
file-logging: true # Write plugin logs under plugins/EcoBalancer/logs/latest.log
require-confirmation: true # Require confirmation for destructive actions
tax-exempt-permission: 'ecobalancer.exempt'
max-deduction-per-player: 0 # 0 = unlimited
min-balance-protection: 0 # 0 = disabled
```

## Tax Ledger and PlaceholderAPI

EcoBalancer records actual tax collected into an internal ledger for `/eb tax fund`, `/eb tax stats [player]`, and tax reports. When PlaceholderAPI is installed, EcoBalancer registers the `ecobal` expansion:

- `%ecobal_tax_fund_balance%`
- `%ecobal_tax_total_collected%`
- `%ecobal_tax_latest_collected%`
- `%ecobal_tax_latest_operation%`
- `%ecobal_player_latest_tax%`
- `%ecobal_player_total_tax%`
- `%ecobal_tax_next_run%`
- `%ecobal_tax_active_policy%`
- `%ecobal_tax_status%`
- `%ecobal_gini%`
- `%ecobal_top1_concentration%`

## Migration

- `/eb migrate check`: Show config/lang version status
- `/eb migrate run`: Apply migration then reload config
- `/eb migrate backup`: Create manual backup files

## Troubleshooting

- Vault not found / plugin disabled: verify Vault and an economy provider are installed.
- Commands lag on large datasets: prefer off-peak runs and tune filters (`tax-filters`).
- Missing language strings: run `/eb migrate run` to merge latest language keys.
- No trends data: snapshots are generated periodically; wait until at least one snapshot is created.

## Dependency Upgrade Strategy

- Keep dependency versions pinned in `pom.xml` properties.
- Review updates regularly (especially `sqlite-jdbc`) and run full command/regression checks after upgrades.
- Prefer upgrading one dependency set at a time (build plugins first, then runtime deps) for easier rollback.

[![Forkers repo roster for @CubeX-MC/EcoBalancer](https://reporoster.com/forks/CubeX-MC/EcoBalancer)](https://github.com/CubeX-MC/EcoBalancer/network/members)
[![Stargazers repo roster for @CubeX-MC/EcoBalancer](https://reporoster.com/stars/CubeX-MC/EcoBalancer)](https://github.com/CubeX-MC/EcoBalancer/stargazers)
