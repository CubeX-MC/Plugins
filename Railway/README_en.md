<div align="center">
  <img src="img/railway.webp" width="112" alt="Railway Logo">
  <h1>Railway Subway System</h1>
  <p>A Minecraft subway transit plugin</p>
  <p>
    <a href="https://github.com/CubeX-MC/Railway"><img src="https://img.shields.io/github/stars/CubeX-MC/Railway?style=flat-square&logo=github&label=Stars" alt="GitHub Stars"></a>
    <a href="https://github.com/CubeX-MC/Railway/network/members"><img src="https://img.shields.io/github/forks/CubeX-MC/Railway?style=flat-square&logo=github&label=Forks" alt="GitHub Forks"></a>
    <a href="https://github.com/CubeX-MC/Railway/issues"><img src="https://img.shields.io/github/issues/CubeX-MC/Railway?style=flat-square&label=Issues" alt="GitHub Issues"></a>
    <img src="https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17+">
    <img src="https://img.shields.io/badge/Paper-1.18%2B-5D8AA8?style=flat-square" alt="Paper 1.18+">
    <img src="https://img.shields.io/badge/Folia-supported-brightgreen?style=flat-square" alt="Folia">
    <a href="https://github.com/CubeX-MC/Railway/actions/workflows/ci.yml"><img src="https://github.com/CubeX-MC/Railway/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  </p>
  <p>
    <a href="README.md">中文</a>
    ·
    <a href="https://github.com/CubeX-MC/Railway/wiki">Wiki</a>
    ·
    <a href="https://discord.com/invite/7tJeSZPZgv">Discord</a>
    ·
    <a href="https://pd.qq.com/s/1n3hpe4e7?b=9">QQ</a>
    ·
    <a href="https://modrinth.com/plugin/metro-railway">Modrinth</a>
  </p>
</div>

Railway is a Minecraft subway transit plugin. Administrators can create automated subway networks. Players can right-click a powered rail to summon a minecart and ride automatically.

Supports Paper 1.18+ and Folia.

## Features

- **Multi-line Network** — Create multiple routes with stops and transfers
- **GUI Administration** — Built-in GUI management
- **Pricing System** — Flat, distance-based, and interval-based fares
- **Permission System** — Per-element trust and ownership
- **Safe Mode** — Protect minecarts from pushing, attacks, and destruction
- **Minecart Portals** — Cross-area and cross-world teleportation
- **Web Map** — BlueMap / Dynmap / Squaremap integration
- **Economy** — Optional Vault support; fares from lines without an owner can be paid into the account named by `economy.account` (empty = destroyed, the old behaviour)
- **Multi-language** — Chinese, English, German, Spanish, and more
- **Folia Support** — Compatible with Folia multithreaded servers

## Basic Concepts

| Concept | Description |
| :--- | :--- |
| **Line** | An ordered list of stops that defines a train route |
| **Stop** | A station area defined by two corner points |
| **StopPoint** | A powered rail where players board |
| **Transfer** | Connections from one Stop to other Lines |

## Learn More

Full documentation is available on the [Railway Wiki](https://github.com/CubeX-MC/Railway/wiki).

---

![](https://bstats.org/signatures/bukkit/Railway.svg)
[![](https://img.shields.io/github/stars/CubeX-MC/Railway?style=social)](https://github.com/CubeX-MC/Railway/stargazers) [![](https://img.shields.io/github/forks/CubeX-MC/Railway?style=social)](https://github.com/CubeX-MC/Railway/network/members)
