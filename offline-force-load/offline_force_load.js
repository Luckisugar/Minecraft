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

// Names are prefixed because KubeJS already declares ResourceLocation and other
// classes as const. Declaring them again aborts the whole script on /reload.
let Il2ClaimMgr = Java.loadClass('dev.ftb.mods.ftbchunks.data.ClaimedChunkManagerImpl')
let Il2TicketController = Java.loadClass('net.neoforged.neoforge.common.world.chunk.TicketController')
let Il2ResLoc = Java.loadClass('net.minecraft.resources.ResourceLocation')
let Il2ResKey = Java.loadClass('net.minecraft.resources.ResourceKey')
let Il2Registries = Java.loadClass('net.minecraft.core.registries.Registries')
let Il2BlockPos = Java.loadClass('net.minecraft.core.BlockPos')
let Il2FluxLoading = Java.loadClass('sonar.fluxnetworks.common.level.FluxChunkLoading')
let Il2FluxDevice = Java.loadClass('sonar.fluxnetworks.common.device.TileFluxDevice')

let ftbTickets = new Il2TicketController(Il2ResLoc.parse('ftbchunks:default'))

let booted = false
let bootTries = 0
let emptyWaits = 0
let ticksSinceLoad = 0
let nextHourlyAt = 0
let followUpAt = 0
let HOUR_MS = 60 * 60 * 1000
let FOLLOW_UP_MS = 45 * 1000

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
  return root.resolve('kubejs').resolve('offline_force_load.json')
}

function readSuspend(path) {
  if (!path) return {}
  try {
    let text = '' + JsonIO.readString(path)
    if (!text.trim()) return {}
    let parsed = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object') return {}
    return parsed
  } catch (e) {
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
  JsonIO.write(path, JsonIO.parseRaw(lines.join('\n') + '\n'))
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

let levelLookupWarned = false

function readMember(obj, name) {
  try {
    let value = obj[name]
    if (typeof value === 'function') return value.call(obj)
    if (value !== undefined && value !== null) return value
  } catch (e) {}
  return null
}

function dimensionName(holder) {
  if (!holder) return ''
  let dim = readMember(holder, 'dimension')
  let target = dim || holder
  let loc = readMember(target, 'location')
  if (loc) return '' + loc
  return '' + target
}

function levelByName(server, dimName) {
  let want = '' + dimName
  try {
    let levels = server.getAllLevels()
    let it = levels.iterator()
    while (it.hasNext()) {
      let level = it.next()
      if (dimensionName(level) === want) return level
    }
  } catch (e) {
    if (!levelLookupWarned) {
      levelLookupWarned = true
      console.warn('Offline force-load could not list levels for ' + want + ': ' + e)
    }
  }
  if (want === 'minecraft:overworld') {
    try {
      if (server.overworld && server.overworld()) return server.overworld()
    } catch (e2) {}
    try {
      if (server.getOverworld && server.getOverworld()) return server.getOverworld()
    } catch (e3) {}
  }
  return null
}

function levelForKey(server, dimKey) {
  let name = dimensionName(dimKey)
  if (!name) name = '' + dimKey
  let level = levelByName(server, name)
  if (level) return level
  try {
    return server.getLevel(dimKey)
  } catch (e2) {
    if (!levelLookupWarned) {
      levelLookupWarned = true
      console.warn('Offline force-load could not open ' + name + ': ' + e2)
    }
    return null
  }
}

function levelForName(server, dimName) {
  let level = levelByName(server, dimName)
  if (level) return level
  try {
    let key = Il2ResKey.create(Il2Registries.DIMENSION, Il2ResLoc.parse('' + dimName))
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

// Rhino treats TicketController.forceChunk(level, owner, ...) as ambiguous between
// the UUID overload and the BlockPos overload, so a direct call throws and
// removes nothing. Bind the exact Java method instead.
let ForceChunks = null
let forceBindFailed = false

function bindForceMethods() {
  if (ForceChunks) return true
  if (forceBindFailed) return false
  try {
    // Loaded from the il2forcechunks mod. KubeJS cannot call forceChunk itself:
    // the BlockPos and UUID overloads are ambiguous, and java.lang.Class is blocked.
    ForceChunks = Java.loadClass('il2.ForceChunks')
    return true
  } catch (e) {
    forceBindFailed = true
    console.error('Offline force-load could not bind forceChunk: ' + e)
    return false
  }
}

function invokeForce(kind, controller, level, owner, x, z, add, ticking) {
  if (kind === 'block') return ForceChunks.block(controller, level, owner, x | 0, z | 0, !!add, !!ticking) == true
  return ForceChunks.uuid(controller, level, owner, x | 0, z | 0, !!add, !!ticking) == true
}

function dropFtbTicket(level, teamId, x, z) {
  if (!bindForceMethods()) return 0
  let removed = 0
  try {
    if (invokeForce('uuid', ftbTickets, level, teamId, x, z, false, true)) removed++
  } catch (e) {
    console.error('FTB ticket drop failed at ' + x + ',' + z + ': ' + e)
  }
  try {
    if (invokeForce('uuid', ftbTickets, level, teamId, x, z, false, false)) removed++
  } catch (e) {
    console.error('FTB non-ticking ticket drop failed at ' + x + ',' + z + ': ' + e)
  }
  return removed
}

function dropFluxTicket(level, pos) {
  if (!bindForceMethods()) return false
  let cx = pos.getX() >> 4
  let cz = pos.getZ() >> 4
  let controller = Il2FluxLoading.CONTROLLER
  try {
    if (invokeForce('block', controller, level, pos, cx, cz, false, true)) return true
  } catch (e) {
    console.error('Flux ticket drop failed at ' + pos + ': ' + e)
    return false
  }
  try {
    return invokeForce('block', controller, level, pos, cx, cz, false, false)
  } catch (e) {
    console.error('Flux non-ticking ticket drop failed at ' + pos + ': ' + e)
    return false
  }
}

function resumeFluxTicket(level, pos) {
  if (!bindForceMethods()) return false
  let cx = pos.getX() >> 4
  let cz = pos.getZ() >> 4
  try {
    return invokeForce('block', Il2FluxLoading.CONTROLLER, level, pos, cx, cz, true, true)
  } catch (e) {
    console.error('Flux ticket resume failed at ' + pos + ': ' + e)
    return false
  }
}

function isFluxDevice(be) {
  if (!be) return false
  try {
    if (Il2FluxDevice.isInstance(be)) return true
  } catch (e) {}
  try {
    return be instanceof Il2FluxDevice
  } catch (e2) {
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
  let manager = Il2ClaimMgr.getInstance()
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

  if (byTeam.size === 0) {
    if (emptyWaits === 0) console.warn('Offline force-load sweep waiting: claims are not loaded yet')
    return false
  }

  let ftbRemoved = 0
  let ftbTried = 0
  let fluxPaused = 0
  let fluxResumed = 0
  let offlineTeams = 0
  let forcedClaims = 0
  let loadedClaims = 0
  let fluxSeen = 0

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
        let pos = new Il2BlockPos(entry.x | 0, entry.y | 0, entry.z | 0)
        let be = null
        try {
          be = level.getBlockEntity(pos)
        } catch (e) {
          be = null
        }
        // Chunk not loaded: keep the position and try again later.
        // A different block, or chunk loading turned off, is forgotten on purpose.
        if (be && (!isFluxDevice(be) || !readMember(be, 'isForcedLoading'))) continue
        if (resumeFluxTicket(level, pos)) fluxResumed++
        else kept.push(entry)
      }
      if (kept.length) suspended[row.id] = kept
      else delete suspended[row.id]
      return
    }

    offlineTeams++
    forcedClaims += row.forced.length
    if (row.forced.length) {
      try {
        row.data.updateChunkTickets(false)
      } catch (e) {
        console.error('FTB updateChunkTickets failed for ' + teamLabel(row.team) + ': ' + e)
      }
      let teamId = row.data.getTeamId()
      for (let i = 0; i < row.forced.length; i++) {
        let pos = row.forced[i].getPos()
        let level = levelForKey(server, readMember(pos, 'dimension'))
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
      let level = levelForKey(server, readMember(cpos, 'dimension'))
      if (!level) continue
      let loaded = loadedChunk(level, cpos.x(), cpos.z())
      if (!loaded) continue
      loadedClaims++
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
        if (!isFluxDevice(be)) continue
        fluxSeen++
        let flagged = false
        try {
          flagged = !!readMember(be, 'isForcedLoading')
        } catch (e) {
          flagged = false
        }
        let bp = be.getBlockPos()
        if (!flagged) {
          // The ticket can still be in NeoForge after the flag was cleared.
          // Drop it, but do not remember it: login should not turn it back on.
          if (dropFluxTicket(level, bp)) pausedHere++
          continue
        }
        let entry = {
          dim: dimensionName(level),
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
    // Tickets can sit on positions we already recorded even when the chunk
    // is not loaded right now. Removing one does not need the block entity.
    for (let n = 0; n < list.length; n++) {
      let known = list[n]
      let knownLevel = levelForName(server, known.dim)
      if (!knownLevel) continue
      let knownPos = new Il2BlockPos(known.x | 0, known.y | 0, known.z | 0)
      if (dropFluxTicket(knownLevel, knownPos)) pausedHere++
    }
    if (list.length) suspended[row.id] = list
    else delete suspended[row.id]
    fluxPaused += pausedHere
    if (pausedHere > 0) {
      console.info('Paused flux chunk loading for ' + teamLabel(row.team) + ' (' + pausedHere + ' devices, nobody online)')
    }
  })

  if (!path) console.warn('Offline force-load sweep has nowhere to save paused flux positions')
  writeSuspend(path, suspended)
  console.info('Offline force-load sweep (' + reason + '): teams ' + byTeam.size + ', offline ' + offlineTeams + ', forced claims ' + forcedClaims + ', loaded claims ' + loadedClaims + ', flux devices ' + fluxSeen + ', FTB removed ' + ftbRemoved + '/' + ftbTried + ', flux paused ' + fluxPaused + ', flux resumed ' + fluxResumed)
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
      if (!sweepOfflineForceLoads(event.server, 'boot')) {
        emptyWaits++
        ticksSinceLoad = 160
        // About a minute of retries. Claims come back a bit after /reload.
        if (emptyWaits >= 20) {
          booted = true
          followUpAt = now + FOLLOW_UP_MS
          nextHourlyAt = now + HOUR_MS
          console.warn('Offline force-load gave up waiting for claims. Next try is the follow-up, then hourly.')
        }
        return
      }
      emptyWaits = 0
      booted = true
      followUpAt = now + FOLLOW_UP_MS
      nextHourlyAt = now + HOUR_MS
    } catch (e) {
      bootTries++
      console.error('Offline force-load sweep failed: ' + e)
      ticksSinceLoad = 160
      if (bootTries >= 5) {
        booted = true
        followUpAt = now + FOLLOW_UP_MS
        nextHourlyAt = now + HOUR_MS
      }
    }
    return
  }
  if (followUpAt && now >= followUpAt) {
    followUpAt = 0
    try {
      sweepOfflineForceLoads(event.server, 'follow-up')
    } catch (e) {
      console.error('Offline force-load follow-up failed: ' + e)
    }
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
