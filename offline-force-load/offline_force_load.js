// FTB Chunks 2101.1.22 turns every saved force-load back on at boot, then only
// turns a team's tickets off when its last member logs out. Flux Networks keeps
// its own ticking block tickets (fluxnetworks:device) and those survive the FTB
// drop. This script does both for teams with nobody online.
//
// Claims and the FTB force-load marks stay. Flux's forced_loading flag stays too,
// so a member logging in can turn those tickets back on. Do not call
// setForcedLoading(false): that flag is saved into the block, and the plug would
// not chunk-load again.
//
// updateChunkTickets(false) logs nothing about NeoForge's result. A ticket stored
// as non-ticking comes back success=false and the chunk keeps ticking. Ask for
// both ticket kinds and trust the boolean.

let ClaimedChunkManagerImpl = Java.loadClass('dev.ftb.mods.ftbchunks.data.ClaimedChunkManagerImpl')
let TicketController = Java.loadClass('net.neoforged.neoforge.common.world.chunk.TicketController')
let ResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')
let ResourceKey = Java.loadClass('net.minecraft.resources.ResourceKey')
let Registries = Java.loadClass('net.minecraft.core.registries.Registries')
let BlockPos = Java.loadClass('net.minecraft.core.BlockPos')
let FluxChunkLoading = Java.loadClass('sonar.fluxnetworks.common.level.FluxChunkLoading')
let TileFluxDevice = Java.loadClass('sonar.fluxnetworks.common.device.TileFluxDevice')
let Files = Java.loadClass('java.nio.file.Files')

let ftbTickets = new TicketController(ResourceLocation.parse('ftbchunks:default'))

let booted = false
let bootTries = 0
let ticksSinceLoad = 0
let nextHourlyAt = 0
let HOUR_MS = 60 * 60 * 1000

function teamLabel(team) {
  try {
    return '' + team.getShortName()
  } catch (e) {
    return '' + team.getId()
  }
}

function overworldReady(server) {
  try {
    if (server.overworld && server.overworld()) return true
  } catch (e) {}
  try {
    if (server.getOverworld && server.getOverworld()) return true
  } catch (e) {}
  return false
}

function serverDir(server) {
  try {
    if (server.getServerDirectory) return server.getServerDirectory()
  } catch (e) {}
  return null
}

function suspendFile(server) {
  let root = serverDir(server)
  if (!root) return null
  return root.resolve('kubejs').resolve('data').resolve('offline_force_load.json')
}

function readSuspend(path) {
  if (!path) return {}
  try {
    if (!Files.exists(path)) return {}
    let text = '' + Files.readString(path)
    if (!text.trim()) return {}
    let parsed = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object') return {}
    return parsed
  } catch (e) {
    console.warn('Offline force-load suspend file unreadable, starting clean: ' + e)
    return {}
  }
}

function writeSuspend(path, data) {
  if (!path) return
  let ids = Object.keys(data)
  let lines = ['{']
  for (let i = 0; i < ids.length; i++) {
    let id = ids[i]
    let list = data[id]
    if (!list || !list.length) continue
    let parts = []
    for (let j = 0; j < list.length; j++) {
      let p = list[j]
      parts.push('{"dim":"' + p.dim + '","x":' + (p.x | 0) + ',"y":' + (p.y | 0) + ',"z":' + (p.z | 0) + '}')
    }
    let comma = ','
    let more = false
    for (let k = i + 1; k < ids.length; k++) {
      if (data[ids[k]] && data[ids[k]].length) more = true
    }
    if (!more) comma = ''
    lines.push('  "' + id + '": [' + parts.join(',') + ']' + comma)
  }
  lines.push('}')
  Files.createDirectories(path.getParent())
  Files.writeString(path, lines.join('\n') + '\n')
}

function teamShouldLoad(data, team) {
  try {
    if (data.canDoOfflineForceLoading()) return true
  } catch (e) {}
  let online = null
  try {
    online = team.getOnlineMembers()
  } catch (e) {}
  return !!(online && online.size() > 0)
}

function levelForKey(server, dimKey) {
  try {
    return server.getLevel(dimKey)
  } catch (e) {
    return null
  }
}

function levelForName(server, dimName) {
  try {
    let key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimName))
    return server.getLevel(key)
  } catch (e) {
    return null
  }
}

function loadedChunk(level, x, z) {
  try {
    return level.getChunkSource().getChunkNow(x, z)
  } catch (e) {
    return null
  }
}

function dropFtbTicket(level, teamId, x, z) {
  let removed = 0
  try {
    if (ftbTickets.forceChunk(level, teamId, x, z, false, true)) removed++
  } catch (e) {
    console.error('FTB ticket drop failed at ' + x + ',' + z + ': ' + e)
  }
  try {
    if (ftbTickets.forceChunk(level, teamId, x, z, false, false)) removed++
  } catch (e) {
    console.error('FTB non-ticking ticket drop failed at ' + x + ',' + z + ': ' + e)
  }
  return removed
}

function dropFluxTicket(level, pos) {
  let cx = pos.getX() >> 4
  let cz = pos.getZ() >> 4
  let controller = FluxChunkLoading.CONTROLLER
  try {
    if (controller.forceChunk(level, pos, cx, cz, false, true)) return true
  } catch (e) {
    console.error('Flux ticket drop failed at ' + pos + ': ' + e)
    return false
  }
  try {
    return !!controller.forceChunk(level, pos, cx, cz, false, false)
  } catch (e) {
    console.error('Flux non-ticking ticket drop failed at ' + pos + ': ' + e)
    return false
  }
}

function resumeFluxTicket(level, pos) {
  let cx = pos.getX() >> 4
  let cz = pos.getZ() >> 4
  try {
    return !!FluxChunkLoading.CONTROLLER.forceChunk(level, pos, cx, cz, true, true)
  } catch (e) {
    console.error('Flux ticket resume failed at ' + pos + ': ' + e)
    return false
  }
}

function entryKey(entry) {
  return entry.dim + ':' + (entry.x | 0) + ':' + (entry.y | 0) + ':' + (entry.z | 0)
}

function sweepOfflineForceLoads(server, reason) {
  if (!overworldReady(server)) {
    console.warn('Offline force-load sweep waiting: overworld is not ready')
    return false
  }
  let manager = ClaimedChunkManagerImpl.getInstance()
  if (!manager) {
    console.warn('Offline force-load sweep skipped: FTB Chunks manager is not loaded')
    return false
  }

  let path = suspendFile(server)
  let suspended = readSuspend(path)
  let byTeam = new Map()
  let claimed = manager.getAllClaimedChunks()
  let it = claimed.iterator()
  while (it.hasNext()) {
    let chunk = it.next()
    let data = chunk.getTeamData()
    if (!data) continue
    let team = null
    try {
      team = data.getTeam()
    } catch (e) {
      team = null
    }
    if (!team || !team.isValid()) continue
    let id = '' + data.getTeamId()
    let row = byTeam.get(id)
    if (!row) {
      row = { data: data, team: team, id: id, claims: [], forced: [] }
      byTeam.set(id, row)
    }
    row.claims.push(chunk)
    if (chunk.isForceLoaded()) row.forced.push(chunk)
  }

  let ftbRemoved = 0
  let ftbTried = 0
  let fluxPaused = 0
  let fluxResumed = 0

  byTeam.forEach(row => {
    if (teamShouldLoad(row.data, row.team)) {
      let list = suspended[row.id]
      if (!list || !list.length) return
      let kept = []
      for (let i = 0; i < list.length; i++) {
        let entry = list[i]
        let level = levelForName(server, entry.dim)
        if (!level) {
          kept.push(entry)
          continue
        }
        let pos = new BlockPos(entry.x | 0, entry.y | 0, entry.z | 0)
        if (resumeFluxTicket(level, pos)) fluxResumed++
      }
      if (kept.length) suspended[row.id] = kept
      else delete suspended[row.id]
      return
    }

    if (row.forced.length) {
      try {
        row.data.updateChunkTickets(false)
      } catch (e) {
        console.error('FTB updateChunkTickets failed for ' + teamLabel(row.team) + ': ' + e)
      }
      let teamId = row.data.getTeamId()
      for (let i = 0; i < row.forced.length; i++) {
        let pos = row.forced[i].getPos()
        let level = levelForKey(server, pos.dimension())
        if (!level) continue
        ftbTried++
        ftbRemoved += dropFtbTicket(level, teamId, pos.x(), pos.z())
      }
    }

    let list = suspended[row.id] || []
    let seen = {}
    for (let i = 0; i < list.length; i++) seen[entryKey(list[i])] = true
    let pausedHere = 0
    for (let i = 0; i < row.claims.length; i++) {
      let cpos = row.claims[i].getPos()
      let level = levelForKey(server, cpos.dimension())
      if (!level) continue
      let loaded = loadedChunk(level, cpos.x(), cpos.z())
      if (!loaded) continue
      let bes = null
      try {
        bes = loaded.getBlockEntities()
      } catch (e) {
        bes = null
      }
      if (!bes) continue
      let values = bes.values().iterator()
      while (values.hasNext()) {
        let be = values.next()
        if (!(be instanceof TileFluxDevice)) continue
        if (!be.isForcedLoading()) continue
        let bp = be.getBlockPos()
        let entry = {
          dim: '' + level.dimension().location(),
          x: bp.getX(),
          y: bp.getY(),
          z: bp.getZ()
        }
        let key = entryKey(entry)
        if (!seen[key]) {
          list.push(entry)
          seen[key] = true
        }
        if (dropFluxTicket(level, bp)) pausedHere++
      }
    }
    if (list.length) suspended[row.id] = list
    else delete suspended[row.id]
    fluxPaused += pausedHere
    if (pausedHere > 0) {
      console.info('Paused flux chunk loading for ' + teamLabel(row.team) + ' (' + pausedHere + ' devices, nobody online)')
    }
  })

  writeSuspend(path, suspended)
  console.info('Offline force-load sweep (' + reason + '): FTB removed ' + ftbRemoved + '/' + ftbTried + ', flux paused ' + fluxPaused + ', flux resumed ' + fluxResumed)
  return true
}

// 200 ticks after this script loads, not 200 ticks of server uptime.
// /reload starts with a huge tick count, and an uptime check fires during the reload.
ServerEvents.tick(event => {
  let now = Date.now()
  if (!booted) {
    ticksSinceLoad++
    if (ticksSinceLoad < 200) return
    try {
      if (!sweepOfflineForceLoads(event.server, 'boot')) return
      booted = true
      nextHourlyAt = now + HOUR_MS
    } catch (e) {
      bootTries++
      console.error('Offline force-load sweep failed: ' + e)
      ticksSinceLoad = 160
      if (bootTries >= 5) {
        booted = true
        nextHourlyAt = now + HOUR_MS
      }
    }
    return
  }
  if (now < nextHourlyAt) return
  nextHourlyAt = now + HOUR_MS
  try {
    sweepOfflineForceLoads(event.server, 'hourly')
  } catch (e) {
    console.error('Offline force-load hourly sweep failed: ' + e)
  }
})

PlayerEvents.loggedIn(event => {
  let server = event.server
  server.scheduleInTicks(40, () => {
    try {
      sweepOfflineForceLoads(server, 'login')
    } catch (e) {
      console.error('Offline force-load sweep after login failed: ' + e)
    }
  })
})

PlayerEvents.loggedOut(event => {
  let server = event.server
  server.scheduleInTicks(40, () => {
    try {
      sweepOfflineForceLoads(server, 'logout')
    } catch (e) {
      console.error('Offline force-load sweep after logout failed: ' + e)
    }
  })
})
