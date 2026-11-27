# Offline force-load

KubeJS server script for Infinity Legacy II, and any NeoForge 1.21.1 pack on the same mods.

FTB Chunks 2101.1.22 turns every saved force-load back on at boot. It only drops a team's tickets when that team's last member logs out. Flux Networks keeps a second ticket, `fluxnetworks:device`, on plugs and points that have chunk loading turned on. Those tickets are not FTB force-loads. Unloading the FTB marks leaves the Flux chunks ticking.

This script runs after boot, again about 45 seconds later, when a player logs in or out, and once an hour. Right after `/reload` the claim list can still be empty, so the boot sweep waits and retries for about a minute. For a team with nobody online and no offline force-load permission it:

- asks NeoForge to remove that team's FTB tickets, both the ticking and the non-ticking kind
- removes Flux chunk-loading tickets in that team's claims

Claims and the FTB force-load squares stay. The Flux "chunk loading" setting stays on the block. When a member logs back in, FTB restores its own tickets and this script puts the Flux tickets back.

The positions it paused are written to `offline_force_load.json` in the server folder. That file is runtime state, not part of this repo.

## Install

Copy `offline_force_load.js` to `kubejs/server_scripts/`.

KubeJS cannot call `TicketController.forceChunk` itself. The BlockPos and UUID overloads look the same to Rhino, and the KubeJS class filter blocks the reflection that would pick one. `java/il2` is a tiny NeoForge mod, `il2forcechunks`, whose `il2.ForceChunks` class has one method per overload. Build that jar (classes plus `java/META-INF/neoforge.mods.toml`) and put it in `mods/`. It only loads on a full server start. `/reload` picks up the script, not the mod.

The running server on Infinity Legacy II already has `mods/il2forcechunks-1.0.0.jar`.

Tested with KubeJS 2101.7.2, FTB Chunks 2101.1.22, FTB Teams 2101.1.11, and Flux Networks 1.21.1-8.0.0. `force_load_mode` in `config/ftbchunks-world.snbt` should stay `never` if empty teams are not supposed to keep chunks loaded.

## Português

O FTB Chunks religa os chunks forçados no boot. O Flux Networks tem um ticket próprio e o servidor continua processando a base de quem está offline. O script tira os dois quando o time não tem ninguém online, e devolve o do Flux quando alguém entra. As claims não são apagadas.
