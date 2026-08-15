<template>
    <div id="map-container">
        <div id="map" :style="{ transform: `rotate(${rotationAngle}deg)` }"></div>
    </div>

    <!-- collapsed control panel re-open button -->
    <button v-if="!controlVisible" class="control-toggle-open" @click="controlVisible = true" title="打开设置">
        ☰ 设置
    </button>

    <div class="control" v-show="controlVisible">
        <div class="control-header">
            <span class="control-title">设置</span>
            <button class="control-close" @click="controlVisible = false" title="收起设置">✕</button>
        </div>
        <div class="status-row">
            <span class="badge" :class="wsConnected ? 'ok' : 'err'">
                {{ wsConnected ? '服务器已连接' : '服务器未连接' }}
            </span>
            <span class="badge" :class="clientConnected ? 'ok' : 'err'">
                {{ clientConnected ? '采集端在线' : '采集端离线' }}
            </span>
            <span class="badge">渲染 {{ fps }} FPS</span>
            <span class="badge" v-if="clientFps != null">采集端 {{ clientFps }} FPS</span>
        </div>

        <div>地图: {{ gameInfo.mapName || '未知' }}</div>
        <div>Tick: {{ gameInfo.tick }}</div>
        <div>平均 tick: {{ avgTick }}</div>

        <button class="rotate-button" @click="rotateMap">旋转地图 90°</button>

        <hr />

        <div v-for="(showTeammate, index) in showTeammates" :key="index" @click="toggleTeammate(index)">
            Show {{ teammateNames[index] }}<input type="checkbox" v-model="showTeammates[index]" />
        </div>

        <div @click="showEnemies = !showEnemies">Show Enemies<input type="checkbox" v-model="showEnemies" /></div>

        <hr />

        <div class="follow-row">
            <label for="follow-select">视角跟随</label>
            <select id="follow-select" v-model="followMode">
                <option value="free">自由视角</option>
                <option value="local">跟随本人</option>
                <option v-for="p in followPlayers" :key="p.key" :value="p.key">
                    {{ p.name || '未知' }}（{{ p.team === 2 ? 'CT' : 'T' }}）
                </option>
            </select>
            <div class="follow-hint">点击玩家标记可快速跟随，点击地图空白取消</div>
        </div>

        <hr />

        <div class="bounds-controls">
            <h4>地图范围调整</h4>
            <div class="bound-control">
                <label>Min X: {{ currentBounds[0][0] }}</label>
                <input type="range" v-model.number="currentBounds[0][0]" :min="-2000" :max="2000" step="1" @input="updateMapBounds" />
            </div>
            <div class="bound-control">
                <label>Min Y: {{ currentBounds[0][1] }}</label>
                <input type="range" v-model.number="currentBounds[0][1]" :min="-2000" :max="2000" step="1" @input="updateMapBounds" />
            </div>
            <div class="bound-control">
                <label>Max X: {{ currentBounds[1][0] }}</label>
                <input type="range" v-model.number="currentBounds[1][0]" :min="-2000" :max="2000" step="1" @input="updateMapBounds" />
            </div>
            <div class="bound-control">
                <label>Max Y: {{ currentBounds[1][1] }}</label>
                <input type="range" v-model.number="currentBounds[1][1]" :min="-2000" :max="2000" step="1" @input="updateMapBounds" />
            </div>
            <button @click="resetBounds" class="reset-btn">重置默认</button>
            <button @click="saveBounds" class="save-btn">保存范围</button>
        </div>
    </div>

    <!-- kill feed (right side, toggleable) -->
    <div class="killfeed-wrap">
        <button v-if="!killFeedVisible" class="killfeed-toggle" @click="killFeedVisible = true">
            💀 击杀记录
        </button>
        <div v-else class="killfeed">
            <div class="killfeed-header">
                <span>💀 击杀记录</span>
                <button class="killfeed-close" title="关闭击杀显示" @click="killFeedVisible = false">✕</button>
            </div>
            <div v-if="killFeed.length === 0" class="killfeed-empty">暂无记录</div>
            <transition-group name="kill" tag="div">
                <div v-for="(k, i) in killFeed" :key="k.key" class="kill-item">
                    <template v-if="k.killer">
                        <span class="kill-name" :class="teamClass(k.killerTeam)">{{ k.killer }}</span>
                        <span class="kill-arrow">→</span>
                    </template>
                    <span class="kill-name" :class="teamClass(k.team)">{{ k.name || '未知玩家' }}</span>
                    <span class="kill-time">{{ formatTime(k.t) }}</span>
                </div>
            </transition-group>
        </div>
    </div>
</template>

<script>
import * as L from 'leaflet'
import enemyIcon from '/src/assets/icons/enemy_icon.png'
import enemyIconHvd from '/src/assets/icons/enemy_icon_hvd.png'
import localPlayerIcon from '/src/assets/icons/localPlayer_icon.png'
import defaultTeammateIcon from '/src/assets/icons/teammate_icon.png'
import teammateIcon0 from '/src/assets/icons/teammate_icon_0.png'
import teammateIcon1 from '/src/assets/icons/teammate_icon_1.png'
import teammateIcon2 from '/src/assets/icons/teammate_icon_2.png'
import teammateIcon3 from '/src/assets/icons/teammate_icon_3.png'
import teammateIcon4 from '/src/assets/icons/teammate_icon_4.png'

import de_ancient_radar from '/src/assets/map/de_ancient_radar.png'
import de_dust2_radar from '/src/assets/map/de_dust2_radar.png'
import de_inferno_radar from '/src/assets/map/de_inferno_radar.png'
import de_mirage_radar from '/src/assets/map/de_mirage_radar.png'
import de_train_radar from '/src/assets/map/de_train_radar.png'
import de_nuke_radar from '/src/assets/map/de_nuke_radar.png'
import de_nuke_lower_radar from '/src/assets/map/de_nuke_lower_radar.png'
import de_overpass_radar from '/src/assets/map/de_overpass_radar.png'
import de_vertigo_radar from '/src/assets/map/de_vertigo_radar.png'
import de_vertigo_lower_radar from '/src/assets/map/de_vertigo_lower_radar.png'
import de_anubis_radar from '/src/assets/map/de_anubis_radar.png'

const teammateIcons = {
    0: teammateIcon0,
    1: teammateIcon1,
    2: teammateIcon2,
    3: teammateIcon3,
    4: teammateIcon4
}

const mapRadar = {
    de_ancient: { map: de_ancient_radar, bounds: [[-294, -289], [217, 213]] },
    de_dust2: { map: de_dust2_radar, bounds: [[-127, -247], [323, 202]] },
    de_inferno: { map: de_inferno_radar, bounds: [[-112, -206], [380, 292]] },
    de_mirage: { map: de_mirage_radar, bounds: [[-340, -322], [172, 188]] },
    de_nuke: {
        needChangeMap: true,
        map: de_nuke_radar,
        mapLower: de_nuke_lower_radar,
        lowerValue: -480,
        bounds: [[-441, -329], [304, 357]]
    },
    de_overpass: { map: de_overpass_radar, bounds: [[-361, -479], [181, 42]] },
    de_vertigo: {
        needChangeMap: true,
        map: de_vertigo_radar,
        mapLower: de_vertigo_lower_radar,
        lowerValue: 11720,
        bounds: [[-223, -312], [172, 84]]
    },
    de_train: { map: de_train_radar, bounds: [[-237, -244], [233, 206]] },
    de_anubis: { map: de_anubis_radar, bounds: [[-330, -315], [156, 185]] }
}

export default {
    data() {
        return {
            allTickVal: 0,
            tickTimes: 0,
            avgTick: 0,
            showTeammates: [true, true, true, true, true],
            showEnemies: true,
            teammateNames: ['Blue', 'Green', 'Yellow', 'Orange', 'Purple'],
            zoom: 1,
            lastMapName: null,
            gameInfo: { mapName: '', tick: 0 },
            map: null,
            imageOverlay: null,
            layerGroup: null,
            markers: new Map(), // key -> { iconMarker, healthMarker, category }
            currentLevel: null,
            bounds: [
                [-330, -315],
                [155, 185]
            ],
            currentBounds: [
                [-330, -315],
                [155, 185]
            ],
            defaultBounds: [
                [-330, -315],
                [155, 185]
            ],
            XSize: 500,
            YSize: 500,
            rotationAngle: 0,
            // websocket / frame pipeline
            ws: null,
            wsConnected: false,
            clientConnected: false,
            clientFps: null,
            latestFrame: null,
            fps: 0,
            _consumedSeq: 0,
            _frameCount: 0,
            // kill feed
            killFeedVisible: localStorage.getItem('killFeedVisible') !== '0',
            killFeed: [],
            // tolerate a few empty frames without dropping the map overlay
            _emptyMapStreak: 0,
            // marker interpolation window (ms): smooths movement between data
            // frames without changing the capture/broadcast frame rate
            _interpMs: 200,
            // left config panel visibility (persisted)
            controlVisible: localStorage.getItem('controlVisible') !== '0',
            // camera follow: 'free' | 'local' | player key
            followMode: 'free',
            followPlayers: [],
            localKey: null,
            _lastPlayers: []
        }
    },
    created() {
        this.connectWs()
    },
    mounted() {
        window.addEventListener('keydown', this.KeyDown, true)
        window.addEventListener('resize', this.onResize)
        this.initMap()
        this.preloadMapImages()
        this._raf = requestAnimationFrame(this.tick)
        this._fpsTimer = setInterval(() => {
            this.fps = this._frameCount
            this._frameCount = 0
        }, 1000)
    },
    beforeUnmount() {
        cancelAnimationFrame(this._raf)
        clearInterval(this._fpsTimer)
        window.removeEventListener('keydown', this.KeyDown, true)
        window.removeEventListener('resize', this.onResize)
        if (this.ws) this.ws.close()
    },
    watch: {
        killFeedVisible(v) {
            localStorage.setItem('killFeedVisible', v ? '1' : '0')
        },
        controlVisible(v) {
            localStorage.setItem('controlVisible', v ? '1' : '0')
        },
        followMode() {
            // refresh follow highlights immediately (no need to wait for a new frame)
            if (this._lastPlayers && this._lastPlayers.length) {
                this.updateMarkers(this._lastPlayers)
            }
        }
    },
    methods: {
        // ------------------------------------------------------------------
        // WebSocket: receive frames, coalesce, and let rAF consume the latest
        // ------------------------------------------------------------------
        connectWs() {
            const wsProtocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
            const wsPort = window.location.port ? `:${window.location.port}` : ''
            const wsUrl = `${wsProtocol}://${window.location.hostname}${wsPort}/ws`
            const ws = new WebSocket(wsUrl)
            this.ws = ws

            ws.onopen = () => {
                this.wsConnected = true
            }
            ws.onclose = () => {
                this.wsConnected = false
                this.clientConnected = false
                // auto reconnect
                setTimeout(() => {
                    if (this.ws === ws) this.connectWs()
                }, 2000)
            }
            ws.onerror = () => {
                /* onclose handles reconnection */
            }
            ws.onmessage = (ev) => {
                let msg
                try {
                    msg = JSON.parse(ev.data)
                } catch {
                    return
                }
                if (!msg || typeof msg !== 'object') return
                if (msg.type === 'status') {
                    this.clientConnected = !!msg.clientConnected
                    const c = msg.clients && msg.clients[0]
                    this.clientFps = c && c.fps != null ? c.fps : null
                    return
                }
                // keep only the newest frame; rAF loop applies it at paint time
                this.latestFrame = msg
            }
        },

        tick() {
            const frame = this.latestFrame
            if (frame && frame.seq !== this._consumedSeq) {
                this._consumedSeq = frame.seq
                this._frameCount++
                this.applyFrame(frame)
            }
            // smooth marker movement between data frames (frame rate unchanged)
            this.interpolateMarkers()
            // camera follow (agent view)
            this.followTarget()
            this._raf = requestAnimationFrame(this.tick)
        },

        /**
         * Linear interpolation between data frames so markers glide instead of
         * jumping. Each marker stores its current render position/angle and its
         * target; new frames retarget from the current render position.
         */
        interpolateMarkers() {
            const now = performance.now()
            for (const entry of this.markers.values()) {
                if (!entry.animStart) continue
                const t = Math.min(1, (now - entry.animStart) / this._interpMs)
                const lat = entry.fromLat + (entry.targetLat - entry.fromLat) * t
                const lng = entry.fromLng + (entry.targetLng - entry.fromLng) * t
                // shortest-path angle interpolation (wrap to [-180, 180])
                let da = entry.targetAngle - entry.fromAngle
                da = ((da + 540) % 360) - 180
                const ang = entry.fromAngle + da * t
                entry.iconMarker.setLatLng([lat, lng])
                entry.healthMarker.setLatLng([lat, lng])
                if (entry.nameMarker) entry.nameMarker.setLatLng([lat, lng])
                if (entry.ringMarker) entry.ringMarker.setLatLng([lat, lng])
                entry.iconMarker.setRotationAngle(ang)
                entry.renderLat = lat
                entry.renderLng = lng
                entry.renderAngle = ang
                if (t >= 1) {
                    entry.animStart = 0
                }
            }
        },

        /** Follow the selected player (or the local player) with the camera. */
        followTarget() {
            if (this.followMode === 'free' || !this.map) return
            let target = null
            if (this.followMode === 'local') {
                for (const entry of this.markers.values()) {
                    if (entry.iconMarker.options.zIndexOffset === 1000) {
                        target = entry
                        break
                    }
                }
            } else {
                target = this.markers.get(this.followMode)
            }
            if (target) {
                this.map.panTo([target.renderLat, target.renderLng], { animate: false })
            }
        },

        // ------------------------------------------------------------------
        // Frame application
        // ------------------------------------------------------------------
        applyFrame(frame) {
            this.gameInfo.mapName = frame.map || ''
            this.gameInfo.tick = frame.dt || 0
            if (frame.dt) {
                this.tickTimes++
                this.allTickVal += frame.dt
                this.avgTick = Math.round(this.allTickVal / this.tickTimes)
            }
            if (frame.map) {
                this._emptyMapStreak = 0
            } else {
                this._emptyMapStreak++
            }
            this.appendKillFeed(frame.killFeed)
            const players = Array.isArray(frame.players) ? frame.players : []
            this._lastPlayers = players
            // refresh the follow-target list from live players
            this.followPlayers = []
            this.localKey = null
            for (const p of players) {
                if (!p.alive) continue
                const key = String(p.entityPawnAddress || `${p.x}|${p.y}`)
                if (p.localPlayer) this.localKey = key
                this.followPlayers.push({
                    key,
                    name: p.name || '',
                    team: p.teamId,
                    local: !!p.localPlayer
                })
            }
            this.updateMarkers(players)
        },

        appendKillFeed(events) {
            if (!Array.isArray(events) || events.length === 0) return
            const seen = new Set(this.killFeed.map((k) => k.key))
            for (const ev of events) {
                const key = `${ev.killer || ''}|${ev.name || ''}|${ev.t || 0}`
                if (seen.has(key)) continue
                seen.add(key)
                this.killFeed.push({
                    key,
                    killer: ev.killer || '',
                    killerTeam: ev.killerTeam,
                    name: ev.name,
                    team: ev.team,
                    t: ev.t
                })
                if (this.killFeed.length > 30) {
                    this.killFeed.shift()
                }
            }
        },

        teamClass(team) {
            return team === 2 ? 'ct' : 't'
        },

        onResize() {
            // keep the map properly sized when the browser window changes
            if (this.map) {
                this.map.invalidateSize()
            }
        },

        preloadMapImages() {
            // preload every radar image so switching maps never flashes blank
            for (const name of Object.keys(mapRadar)) {
                const cfg = mapRadar[name]
                const img = new Image()
                img.src = cfg.map
                if (cfg.mapLower) {
                    const img2 = new Image()
                    img2.src = cfg.mapLower
                }
            }
        },

        formatTime(ms) {
            if (!ms) return ''
            const d = new Date(ms)
            const p = (n) => String(n).padStart(2, '0')
            return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
        },

        updateMarkers(players) {
            const mapName = this.gameInfo.mapName
            const knowMap = !!mapRadar[mapName]
            if (knowMap) {
                this.initKnowMap()
            } else if (this._emptyMapStreak > 20) {
                // only drop the map after a sustained empty-map period (>~0.7s),
                // so transient empty frames do not make the map flicker
                this.initUnknowMap()
            }

            const visibleKeys = new Set()
            let localPlayer = null

            for (const item of players) {
                if (!item.alive) continue
                if (item.localPlayer) localPlayer = item

                if (item.enemy && !this.showEnemies) continue
                // teammates: hide only when they have a color and that color's
                // checkbox is off; teammates without a color (-1) always show
                if (!item.enemy && !item.localPlayer && item.compTeammateColor !== -1 && !this.showTeammates[item.compTeammateColor]) continue

                const key = String(item.entityPawnAddress || `${item.x}|${item.y}`)
                visibleKeys.add(key)

                const point = L.latLng(item.x / 10, item.y / 10)
                const angles = item.localPlayer ? (knowMap ? item.angles : 0) : item.angles
                // the followed player's marker turns green + gets a green ring
                const isFollowed =
                    this.followMode === key || (this.followMode === 'local' && key === this.localKey)

                let entry = this.markers.get(key)
                if (!entry) {
                    const iconMarker = L.marker(point, {
                        icon: this.pickIcon(item, isFollowed),
                        rotationAngle: angles,
                        zIndexOffset: item.localPlayer ? 1000 : 0
                    }).addTo(this.layerGroup)
                    const healthMarker = L.marker(point, {
                        icon: this.healthIcon(item.health),
                        interactive: false,
                        keyboard: false
                    }).addTo(this.layerGroup)
                    const nameMarker = L.marker(point, {
                        icon: this.nameIcon(item, isFollowed),
                        interactive: false,
                        keyboard: false
                    }).addTo(this.layerGroup)
                    // click a player marker to follow that player (agent view)
                    iconMarker.on('click', () => {
                        this.followMode = item.localPlayer ? 'local' : key
                    })
                    entry = {
                        iconMarker,
                        healthMarker,
                        nameMarker,
                        ringMarker: null,
                        category: this.categoryOf(item, isFollowed),
                        // interpolation state
                        animStart: 0,
                        fromLat: point.lat,
                        fromLng: point.lng,
                        targetLat: point.lat,
                        targetLng: point.lng,
                        fromAngle: angles,
                        targetAngle: angles,
                        renderLat: point.lat,
                        renderLng: point.lng,
                        renderAngle: angles
                    }
                    this.markers.set(key, entry)
                } else {
                    // retarget: animate from the CURRENT render position to the new one
                    const moved = entry.targetLat !== point.lat || entry.targetLng !== point.lng
                    const turned = entry.targetAngle !== angles
                    if (moved || turned) {
                        entry.fromLat = entry.renderLat
                        entry.fromLng = entry.renderLng
                        entry.targetLat = point.lat
                        entry.targetLng = point.lng
                        entry.fromAngle = entry.renderAngle
                        entry.targetAngle = angles
                        entry.animStart = performance.now()
                    }
                    const cat = this.categoryOf(item, isFollowed)
                    if (cat !== entry.category) {
                        entry.iconMarker.setIcon(this.pickIcon(item, isFollowed))
                        entry.category = cat
                    }
                    const hp = entry.healthMarker
                    if (hp._hp !== item.health) {
                        hp.setIcon(this.healthIcon(item.health))
                        hp._hp = item.health
                    }
                    // name label (cheap: only when it changed)
                    if (entry.nameMarker._name !== item.name || entry.nameMarker._follow !== isFollowed) {
                        entry.nameMarker.setIcon(this.nameIcon(item, isFollowed))
                        entry.nameMarker._name = item.name
                        entry.nameMarker._follow = isFollowed
                    }
                }

                // green follow ring (shown only for the followed player)
                if (isFollowed && !entry.ringMarker) {
                    entry.ringMarker = L.marker(entry.renderLat !== undefined ? [entry.renderLat, entry.renderLng] : point, {
                        icon: this.ringIcon(),
                        interactive: false,
                        keyboard: false,
                        zIndexOffset: 900
                    }).addTo(this.layerGroup)
                } else if (!isFollowed && entry.ringMarker) {
                    this.layerGroup.removeLayer(entry.ringMarker)
                    entry.ringMarker = null
                }
            }

            // remove markers that disappeared
            for (const [key, entry] of this.markers) {
                if (!visibleKeys.has(key)) {
                    this.layerGroup.removeLayer(entry.iconMarker)
                    this.layerGroup.removeLayer(entry.healthMarker)
                    if (entry.nameMarker) this.layerGroup.removeLayer(entry.nameMarker)
                    if (entry.ringMarker) this.layerGroup.removeLayer(entry.ringMarker)
                    this.markers.delete(key)
                    // stop following a player that left/died
                    if (this.followMode === key) {
                        this.followMode = 'free'
                    }
                }
            }

            // two-level maps (nuke / vertigo): switch image based on local player z
            if (knowMap && mapRadar[mapName].needChangeMap) {
                let level = null
                if (localPlayer) {
                    level = localPlayer.z > mapRadar[mapName].lowerValue ? 'upper' : 'lower'
                }
                if (level !== null && level !== this.currentLevel) {
                    this.currentLevel = level
                    const img = level === 'upper' ? mapRadar[mapName].map : mapRadar[mapName].mapLower
                    if (this.imageOverlay != null) this.map.removeLayer(this.imageOverlay)
                    this.imageOverlay = L.imageOverlay(img, this.currentBounds, {
                        interactive: true,
                        opacity: 1
                    }).addTo(this.map)
                }
            }
        },

        pickIcon(item, follow) {
            let iconUrl
            if (item.compTeammateColor === -1) {
                if (item.localPlayer) iconUrl = localPlayerIcon
                else if (item.enemy) iconUrl = item.sameLevel ? enemyIcon : enemyIconHvd
                else iconUrl = defaultTeammateIcon
            } else {
                if (item.localPlayer) iconUrl = teammateIcons[item.compTeammateColor] || localPlayerIcon
                else if (item.enemy) iconUrl = item.sameLevel ? enemyIcon : enemyIconHvd
                else iconUrl = teammateIcons[item.compTeammateColor] || defaultTeammateIcon
            }
            return L.icon({
                iconUrl: iconUrl,
                iconSize: [40, 40],
                iconAnchor: [20, 26.5],
                // followed player's marker turns green
                className: follow ? 'follow-icon' : ''
            })
        },

        categoryOf(item, follow) {
            return `${item.compTeammateColor}|${item.enemy}|${item.localPlayer}|${item.sameLevel}|${follow ? 1 : 0}`
        },

        healthIcon(health) {
            // low health turns the label red for quick readability
            const cls = health <= 30 ? 'low' : health <= 60 ? 'mid' : ''
            return L.divIcon({
                className: 'health-marker',
                html: `<div class="health-text ${cls}">${health}</div>`,
                iconSize: [40, 40],
                iconAnchor: [20, 8]
            })
        },

        ringIcon() {
            return L.divIcon({
                className: 'follow-ring-marker',
                html: '<div class="follow-ring"></div>',
                iconSize: [52, 52],
                iconAnchor: [26, 26]
            })
        },

        nameIcon(item, follow) {
            const name = item.name || ''
            const team = item.teamId
            return L.divIcon({
                className: 'name-marker',
                html: `<div class="name-text ${team === 2 ? 'ct' : 't'}${follow ? ' follow' : ''}">${this.escapeHtml(name)}</div>`,
                iconSize: [0, 0],
                iconAnchor: [0, 0]
            })
        },

        escapeHtml(s) {
            return String(s).replace(/[&<>"']/g, (c) => ({
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&#39;'
            }[c]))
        },

        // ------------------------------------------------------------------
        // Map images / bounds
        // ------------------------------------------------------------------
        initKnowMap() {
            const mapName = this.gameInfo.mapName
            if (this.lastMapName !== mapName) {
                this.lastMapName = mapName
                this.allTickVal = 0
                this.tickTimes = 0
                this.avgTick = 0
                this.currentLevel = null
                if (this.imageOverlay != null) {
                    this.map.removeLayer(this.imageOverlay)
                    this.imageOverlay = null
                }

                const savedBounds = localStorage.getItem(`bounds_${mapName}`)
                if (savedBounds) {
                    this.currentBounds = JSON.parse(savedBounds)
                } else {
                    this.currentBounds = JSON.parse(JSON.stringify(mapRadar[mapName].bounds))
                }

                this.imageOverlay = L.imageOverlay(mapRadar[mapName].map, this.currentBounds, {
                    interactive: true,
                    opacity: 1
                }).addTo(this.map)
                this.map.setView(this.imageOverlay.getBounds().getCenter())
            }
        },

        initUnknowMap() {
            if (this.imageOverlay != null) {
                this.allTickVal = 0
                this.tickTimes = 0
                this.avgTick = 0
                this.map.removeLayer(this.imageOverlay)
                this.imageOverlay = null
                this.currentLevel = null
            }
            this.currentBounds = JSON.parse(JSON.stringify(this.defaultBounds))
        },

        // ------------------------------------------------------------------
        // Controls
        // ------------------------------------------------------------------
        toggleTeammate(index) {
            this.showTeammates[index] = !this.showTeammates[index]
        },
        rotateMap() {
            this.rotationAngle = (this.rotationAngle + 90) % 360
            const mapContainer = document.getElementById('map')
            mapContainer.style.transform = `rotate(${this.rotationAngle}deg)`
        },
        updateMapBounds() {
            const mapName = this.gameInfo.mapName
            if (this.imageOverlay && mapRadar[mapName]) {
                this.map.removeLayer(this.imageOverlay)
                this.imageOverlay = L.imageOverlay(mapRadar[mapName].map, this.currentBounds, {
                    interactive: true,
                    opacity: 1
                }).addTo(this.map)
                this.map.setView(this.imageOverlay.getBounds().getCenter())
            }
        },
        resetBounds() {
            const mapName = this.gameInfo.mapName
            if (mapName && mapRadar[mapName]) {
                this.currentBounds = JSON.parse(JSON.stringify(mapRadar[mapName].bounds))
            } else {
                this.currentBounds = JSON.parse(JSON.stringify(this.defaultBounds))
            }
            this.updateMapBounds()
        },
        saveBounds() {
            const mapName = this.gameInfo.mapName
            if (mapName && mapRadar[mapName]) {
                mapRadar[mapName].bounds = JSON.parse(JSON.stringify(this.currentBounds))
                localStorage.setItem(`bounds_${mapName}`, JSON.stringify(this.currentBounds))
            }
        },
        reloadMap() {
            if (this.imageOverlay != null) {
                this.map.removeLayer(this.imageOverlay)
            }
            this.currentBounds[1] = [this.currentBounds[0][0] + this.XSize, this.currentBounds[0][1] + this.YSize]
            const mapName = this.gameInfo.mapName
            if (mapName && mapRadar[mapName]) {
                this.imageOverlay = L.imageOverlay(mapRadar[mapName].map, this.currentBounds).addTo(this.map)
            }
        },
        KeyDown(e) {
            switch (e.keyCode) {
                case 96:
                    this.XSize = 500
                    this.YSize = 500
                    this.reloadMap()
                    break
                case 98:
                    this.XSize += 1
                    this.reloadMap()
                    break
                case 97:
                    this.XSize -= 1
                    this.reloadMap()
                    break
                case 101:
                    this.YSize += 1
                    this.reloadMap()
                    break
                case 100:
                    this.YSize -= 1
                    this.reloadMap()
                    break
                case 37:
                    this.currentBounds[0][1] += 1
                    this.reloadMap()
                    break
                case 38:
                    this.currentBounds[0][0] -= 1
                    this.reloadMap()
                    break
                case 39:
                    this.currentBounds[0][1] -= 1
                    this.reloadMap()
                    break
                case 40:
                    this.currentBounds[0][0] += 1
                    this.reloadMap()
                    break
            }
        },
        initMap() {
            this.map = L.map('map', {
                center: [0, 0],
                zoom: this.zoom,
                crs: L.CRS.Simple,
                maxZoom: 3,
                minZoom: 0
            })
            // click empty map space to stop following
            this.map.on('click', () => {
                this.followMode = 'free'
            })
            this.layerGroup = L.layerGroup().addTo(this.map)
        }
    }
}
</script>

<style scoped>
#map-container {
    position: absolute;
    width: 100%;
    height: 100%;
    top: 0;
    left: 0;
    z-index: 0;
}

#map {
    position: absolute;
    width: 100%;
    height: 100%;
    top: 0;
    left: 0;
    z-index: 0;
    transition: transform 0.5s ease-in-out;
}

.control {
    position: absolute;
    top: 20px;
    left: 10px;
    z-index: 1000;
    background: rgba(24, 29, 38, 0.92);
    color: #dbe2ea;
    border: 1px solid #2c3542;
    border-radius: 10px;
    padding: 8px 12px 10px;
    max-height: calc(100vh - 40px);
    overflow-y: auto;
    font-size: 13px;
    min-width: 190px;
    backdrop-filter: blur(4px);
}

.control-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 6px;
    font-weight: bold;
}

.control-close {
    background: none;
    border: none;
    color: #9aa7b5;
    font-size: 14px;
    cursor: pointer;
    padding: 0 4px;
}

.control-close:hover {
    color: #fff;
}

.control-toggle-open {
    position: absolute;
    top: 20px;
    left: 10px;
    z-index: 1000;
    background: rgba(24, 29, 38, 0.92);
    color: #dbe2ea;
    border: 1px solid #2c3542;
    border-radius: 8px;
    padding: 6px 12px;
    font-size: 13px;
    cursor: pointer;
    backdrop-filter: blur(4px);
}

.control-toggle-open:hover {
    background: #2c3542;
}

.status-row {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 8px;
}

.badge {
    font-size: 11px;
    padding: 2px 8px;
    border-radius: 10px;
    background: #2c3542;
}

.badge.ok {
    background: #1d5c34;
    color: #8ff0b0;
}

.badge.err {
    background: #5c1d1d;
    color: #f0a0a0;
}

.rotate-button {
    background: #2c3542;
    color: #dbe2ea;
    border: 1px solid #3a4656;
    padding: 5px 10px;
    border-radius: 5px;
    font-size: 12px;
    cursor: pointer;
    margin: 6px 0;
}

.rotate-button:hover {
    background: #3a4656;
}

.follow-row {
    margin: 6px 0;
}

.follow-row label {
    display: block;
    font-size: 12px;
    margin-bottom: 3px;
}

.follow-row select {
    width: 100%;
    background: #2c3542;
    color: #dbe2ea;
    border: 1px solid #3a4656;
    border-radius: 5px;
    padding: 4px;
    font-size: 12px;
}

.follow-hint {
    font-size: 10px;
    color: #6b7683;
    margin-top: 3px;
}

.control div {
    margin-bottom: 5px;
}

.bounds-controls h4 {
    margin: 0 0 8px 0;
    font-size: 13px;
}

.bound-control label {
    display: block;
    font-size: 11px;
    opacity: 0.8;
    margin-bottom: 2px;
}

.bound-control input[type='range'] {
    width: 100%;
    margin-bottom: 4px;
}

.reset-btn,
.save-btn {
    background-color: #2f6fde;
    color: white;
    border: none;
    padding: 5px 10px;
    border-radius: 5px;
    font-size: 12px;
    cursor: pointer;
    margin-right: 5px;
    margin-top: 5px;
}

.save-btn {
    background-color: #1d7a45;
}

hr {
    margin: 8px 0;
    border: 0;
    border-top: 1px solid #2c3542;
}

/* ---- kill feed (right side) ---- */
.killfeed-wrap {
    position: absolute;
    top: 20px;
    right: 12px;
    z-index: 1000;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
}

.killfeed-toggle {
    background: rgba(24, 29, 38, 0.92);
    color: #dbe2ea;
    border: 1px solid #2c3542;
    border-radius: 8px;
    padding: 6px 12px;
    font-size: 13px;
    cursor: pointer;
}

.killfeed-toggle:hover {
    background: #2c3542;
}

.killfeed {
    width: 240px;
    max-height: 60vh;
    overflow-y: auto;
    background: rgba(24, 29, 38, 0.92);
    color: #dbe2ea;
    border: 1px solid #2c3542;
    border-radius: 10px;
    padding: 8px 10px;
    font-size: 12px;
    backdrop-filter: blur(4px);
}

.killfeed-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-weight: bold;
    margin-bottom: 6px;
}

.killfeed-close {
    background: none;
    border: none;
    color: #9aa7b5;
    font-size: 14px;
    cursor: pointer;
    padding: 0 4px;
}

.killfeed-close:hover {
    color: #fff;
}

.killfeed-empty {
    color: #6b7683;
    text-align: center;
    padding: 8px 0;
}

.kill-item {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 3px 0;
    border-bottom: 1px solid #232b36;
}

.kill-item:last-child {
    border-bottom: none;
}

.kill-team {
    flex: 0 0 auto;
    font-size: 10px;
    font-weight: bold;
    padding: 1px 5px;
    border-radius: 4px;
}

.kill-team.ct {
    background: #1d4d8f;
    color: #a8d3ff;
}

.kill-team.t {
    background: #8f6a1d;
    color: #ffe9b0;
}

.kill-name {
    flex: 1 1 auto;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: bold;
}

.kill-name.ct {
    color: #7fc0ff;
}

.kill-name.t {
    color: #ffd77a;
}

.kill-arrow {
    flex: 0 0 auto;
    color: #6b7683;
    margin: 0 2px;
}

.kill-time {
    flex: 0 0 auto;
    color: #6b7683;
    font-size: 10px;
}

/* kill item enter/leave animation */
.kill-enter-active {
    transition: all 0.3s ease-out;
}

.kill-enter-from {
    opacity: 0;
    transform: translateX(24px);
}

.kill-leave-active {
    transition: all 0.3s ease-in;
    position: absolute;
    right: 10px;
    width: 100%;
}

.kill-leave-to {
    opacity: 0;
    transform: translateX(24px);
}

/* ---- mobile adaptation ---- */
@media (max-width: 768px) {
    .control {
        top: 12px;
        left: 8px;
        font-size: 12px;
        min-width: 158px;
        max-width: 70vw;
        max-height: calc(100vh - 24px);
        padding: 6px 10px 8px;
    }

    /* the map-bounds sliders are not practical on touch screens */
    .bounds-controls {
        display: none;
    }

    .control-toggle-open {
        top: 12px;
        left: 8px;
        font-size: 13px;
        padding: 8px 12px;
    }

    .killfeed-wrap {
        top: 12px;
        right: 8px;
    }

    .killfeed {
        width: 52vw;
        max-width: 210px;
        font-size: 11px;
    }

    .killfeed-toggle {
        font-size: 12px;
        padding: 8px 10px;
    }

    .status-row {
        gap: 4px;
    }
}
</style>

<style>
/* health labels rendered by Leaflet divIcon (global, outside scoped styles) */
.health-text {
    text-align: center;
    font-size: 11px;
    font-weight: bold;
    color: #ffffff;
    text-shadow: 0 0 3px #000000, 0 0 3px #000000;
}

.health-text.mid {
    color: #ffd77a;
}

.health-text.low {
    color: #ff6b6b;
}

/* player name labels (Leaflet divIcon content, global) */
.name-marker {
    pointer-events: none;
}

.name-text {
    position: relative;
    transform: translate(-50%, -42px);
    font-size: 11px;
    font-weight: bold;
    color: #ffffff;
    text-shadow: 0 0 3px #000000, 0 0 3px #000000;
    white-space: nowrap;
}

.name-text.ct {
    color: #7fc0ff;
}

.name-text.t {
    color: #ffd77a;
}

.name-text.follow {
    color: #7dff7d;
}

/* followed player's marker icon turns green */
.follow-icon {
    filter: brightness(0.9) sepia(1) saturate(4) hue-rotate(55deg);
}

/* green ring around the followed player */
.follow-ring-marker {
    pointer-events: none;
}

.follow-ring {
    width: 52px;
    height: 52px;
    border: 3px solid #2eff6e;
    border-radius: 50%;
    box-shadow: 0 0 10px #2eff6e, inset 0 0 6px rgba(46, 255, 110, 0.6);
}
</style>
