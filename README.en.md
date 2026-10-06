# MTR BR Signal Addon

[English](README.en.md) | [简体中文](README.zh-CN.md)

![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-62a35a?style=flat-square) ![Forge 47.4.18](https://img.shields.io/badge/Forge-47.4.18-f59e0b?style=flat-square) ![MTR 4.0.3](https://img.shields.io/badge/MTR-4.0.3-3b82f6?style=flat-square) ![License MIT](https://img.shields.io/badge/license-MIT-8b5cf6?style=flat-square)

**Build a railway that feels signalled, not merely decorated.**

[MTR](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) BR Signal Addon adds a server-authoritative British-style signalling layer to Minecraft Transit Railway. Trains occupy real sections, request complete routes, receive safe movement authority and display that state through colour-light signals, route indicators and shunting signals.

It also provides a dispatcher, a private Web UI, BR-aware redstone sensors and polished railway signs for building a complete control system around an MTR network.

## Features

| Feature | What it does |
|---|---|
| **Fixed-block protection** | Tracks occupancy, reservations, locks, junction resources and safe release. |
| **Route authority** | Projects only the safe prefix of a requested route and enforces it server-side. |
| **British signal displays** | Provides colour-light signals, LED route indicators, repeaters and position-light shunts. |
| **Dispatcher tools** | Adds an in-game console, searchable Web UI, audits and private operator tokens. |
| **BR redstone sensors** | Triggers redstone from authorized routes, approach sections and optional target sections. |
| **Railway signs** | Adds editable PSR/AWI speed signs, text signs and signal brackets. |

The signalling engine is separated from the display layer through public device, face, authority and display interfaces. Movement authority therefore remains independent from a signal’s shape, lamp arrangement or artwork.

## Install

Requires Minecraft 1.20.1, Forge 47.4.18 and MTR Forge 4.0.3. Put `mtr_brsignal_addon-0.2.0.jar` in the `mods/` folder on both the client and server.

This build uses network protocol `11`; clients and servers must use the same addon build.

## First setup

1. Place MTR signals, rail nodes and any displays you want to use.
2. Right-click a signal with the signal debug tool and bind its controlled node.
3. Select the signal with the route tool, then Shift-right-click the destination node.
4. Shift-right-click a route indicator or repeater, then the main signal, to bind the display.
5. Run `/mtrbr protection regenerate` after the initial topology is ready.

## Routes and displays

Colour-light indicators use `route=1` through `route=6`. LED displays use `path=...`, and position-light shunts use `shunt=name`. Values can be combined with `||`:

```text
route=2 || path=1 || shunt=yard_1
```

LED indicators support numbers, letters, directions and common short codes, allowing one signalling system to describe stations, yards and junctions.

<p align="center"><img src="build/previews/path-textures-authored-preview.png" width="720" alt="LED path indicator preview sheet"></p>

<p align="center"><img src="build/previews/all-route-families.png" width="720" alt="All colour-light route indicator types, front view"></p>

## Shunting

A position-light shunt must be bound to a main signal and a matching `shunt=` route. When cleared, the main signal may remain red while the shunt shows two white lights. After passing a shunt entry, a train may request the next Block immediately; consecutive shunts advance one node at a time until ordinary signal authority resumes.

<p align="center"><img src="build/previews/position-light-preview.png" width="720" alt="Position-light shunt preview sheet"></p>

Signals recalculate every 10 ticks, synchronize changes immediately and perform a 20-tick fallback sync. Removing the last position-light device disables shunt mode.

## Redstone train sensors

The MTR `mtr:train_sensor` keeps its native nearby-train detection unless BR mode is enabled. To open the addon settings, use the signal debug tool on the sensor; an ordinary right-click does not open this screen. Sensors may be placed anywhere, including away from the track.

BR mode uses two independent binding groups: **Approach Sections** define how early a train can trigger the sensor, while optional **Targeted Sections** require the train’s forward path to reach one of them. Every target must also be an approach section.

The server evaluates each authorized train individually. A trigger requires the train’s active BR authorization to overlap an approach section. When targets are configured, the same train’s immutable forward path must also reach a target after its current position. A train that shares an approach section but takes another branch does not trigger the sensor.

Output can be `CONTINUOUS`, which stays powered while the condition is true, or `PULSE`, which emits one configurable pulse on each rising condition edge. The in-game screen controls the name, mode, output type and pulse length. Select `BIND / WEB` to open the English Web UI for map-based selection.

The Web UI lists sensor names, dimensions, coordinates, loaded state and output state. Binding supports `BIND`, `CONFIRM`, `CANCEL`, `UNBIND` and `CLEAR ALL`; hold Ctrl to select multiple sections. Web changes require an authenticated operator token and permission level 2.

## Speed signs

PSR signs are red circles and AWI signs are yellow triangles. Both support single- and double-line variants, editable lettering and left, both-way or right arrows. Centre, bottom and top mounting options are available. These signs are decorative and do not change train speed, routing or authority.

<p align="center"><img src="build/previews/speed_signs.png" width="720" alt="PSR and AWI speed sign preview sheet"></p>

## Text signs and brackets

Text signs use a grey circular border and black lettering. The debug tool edits one to three letters or digits; text is normalized to uppercase, scaled and centred automatically. Separate controls select the plate and pole mounting.

Brackets are available in type (1), type (2), double-sided versions and type (1) left/right guards. Type (1) and guard variants support 16 orientations; the other variants use the four cardinal directions.

## Dispatcher and Web UI

Open the dispatcher with the console block or dispatcher tool. The Web UI provides a live topology map, section state, signal state, vehicle requests, route editing and operator controls. Search, status filters, sorting and full-value tooltips keep large networks manageable.

```text
/mtrbr requests
/mtrbr approve <vehicle_code>
/mtrbr revoke_pending <vehicle_code>
/mtrbr manual_override <vehicle_code> <true|false>
/mtrbr priority <vehicle_code> <value>
/mtrbr audit
/mtrbr protection regenerate
/mtrbr web_token generate
/mtrbr web_token list
/mtrbr web_token revocation
```

Web controls require an authenticated operator token. Tokens are private, device-bound and limited to permission level 2. On a dedicated server, configure `web_public_host` before opening the Web UI.

## Build and develop

Builds require JDK 17 and Pillow. Position-light previews also require NumPy:

```powershell
python -m pip install -r tools/requirements.txt
.\gradlew.bat build --no-daemon
```

```powershell
python tools/preview_indicators.py
python tools/preview_path_textures.py
```

## Compatibility and licences

The addon does not modify MTR source code or its original JAR. It integrates through addon-side extensions and Mixins. Update client and server together whenever the addon build or network protocol changes.

Source code is released under the [MIT License](LICENSE). Alte DIN 1451 Mittelschrift Regular and Terminus Regular use SIL OFL 1.1. Font, MTR signal-pole and other third-party sources are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This project is primarily implemented with ChatGPT.
