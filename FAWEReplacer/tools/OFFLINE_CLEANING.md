# Offline MCA Cleaning

Use this workflow when entity cleanup should not load chunks through Paper, or
when a world has bad chunk data that makes in-server cleanup risky.

Always stop the server and back up the world before applying changes.

## Entity Cleanup

This scans `world/entities/*.mca` and removes matching entities inside the X/Z
range. It does not touch terrain chunks in `world/region`.

Dry run:

```powershell
python tools\clean_entities_mca.py `
  --mode entities `
  --entities-dir "C:\path\to\server\world\entities" `
  --min-x -80000 --max-x 80000 `
  --min-z -80000 --max-z 80000
```

Apply:

```powershell
python tools\clean_entities_mca.py `
  --mode entities `
  --entities-dir "C:\path\to\server\world\entities" `
  --min-x -80000 --max-x 80000 `
  --min-z -80000 --max-z 80000 `
  --apply
```

Default removed entity ids:

```text
minecraft:armor_stand
minecraft:item_frame
minecraft:glow_item_frame
minecraft:minecart
minecraft:chest_minecart
minecraft:hopper_minecart
```

## Container Cleanup

This scans `world/region/*.mca` and clears `Items` from block entities inside
the X/Z range. By default it also removes `LootTable` and `LootTableSeed`, so
unopened generated loot containers will not refill when opened later.

Dry run:

```powershell
python tools\clean_entities_mca.py `
  --mode containers `
  --region-dir "C:\path\to\server\world\region" `
  --min-x -80000 --max-x 80000 `
  --min-z -80000 --max-z 80000
```

Apply:

```powershell
python tools\clean_entities_mca.py `
  --mode containers `
  --region-dir "C:\path\to\server\world\region" `
  --min-x -80000 --max-x 80000 `
  --min-z -80000 --max-z 80000 `
  --apply
```

To clear item lists but preserve generated loot tables:

```powershell
python tools\clean_entities_mca.py `
  --mode containers `
  --region-dir "C:\path\to\server\world\region" `
  --min-x -80000 --max-x 80000 `
  --min-z -80000 --max-z 80000 `
  --keep-loot-tables `
  --apply
```

To restrict container cleanup to specific block entity ids, repeat
`--container-id`, for example:

```powershell
python tools\clean_entities_mca.py `
  --mode containers `
  --region-dir "C:\path\to\server\world\region" `
  --container-id minecraft:chest `
  --container-id minecraft:barrel `
  --apply
```

## Notes

- The script defaults to dry-run mode. Nothing is written unless `--apply` is
  present.
- On apply, only modified `.mca` files are backed up, next to the scanned
  directory by default.
- Use `world\entities` for entity cleanup and `world\region` for container
  cleanup. Do not point the entity mode at `region`, or container mode at
  `entities`.
- This tool does not repair corrupt terrain chunks. It only edits NBT payloads
  it can parse.
