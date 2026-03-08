import { LyricPlayer, BackgroundRender, PixiRenderer, MeshGradientRenderer } from '@applemusic-like-lyrics/core'
import '@applemusic-like-lyrics/core/style.css'

const PLAYER_BACKGROUND = 'transparent'
const SEEK_THRESHOLD_MS = 900
const SEEK_HOLD_MS = 180

const QUALITY_PROFILE = {
  alignAnchor: 'center',
  alignPosition: 0.35,
  enableSpring: true,
  enableScale: true,
  enableBlur: true,
  hidePassedLines: false,
  wordFadeWidth: 0.5,
  linePosYSpringParams: {
    mass: 0.9,
    damping: 15,
    stiffness: 90,
  },
  lineScaleSpringParams: {
    mass: 2,
    damping: 25,
    stiffness: 100,
  },
}

const LITE_PROFILE = {
  ...QUALITY_PROFILE,
  enableSpring: false,
  enableBlur: false,
  wordFadeWidth: 0.15,
}

const DEFAULT_BG_PROFILE = {
  renderer: 'pixi',
  fps: 60,
  flowSpeed: 2.2,
  renderScale: 0.8,
  staticMode: false,
  lowFreqVolume: 1,
  hasLyric: true,
}

function logToAndroid(message) {
  if (typeof Android !== 'undefined' && Android?.log) {
    Android.log(message)
  }
}

function toWordEntries(line) {
  if (Array.isArray(line?.words) && line.words.length > 0) {
    const mapped = line.words.map((word) => ({
      word: String(word?.word ?? ''),
      startTime: Number(word?.startTime ?? line?.startTime ?? 0),
      endTime: Number(word?.endTime ?? line?.endTime ?? line?.startTime ?? 0),
    }))

    return mapped.map((word) => {
      const startTime = Number.isFinite(word.startTime) ? word.startTime : 0
      const endTime = Number.isFinite(word.endTime) ? word.endTime : startTime
      return {
        ...word,
        startTime,
        endTime: Math.max(startTime, endTime),
      }
    })
  }

  const lineText = String(line?.text ?? '').trim()
  return [
    {
      word: lineText.length > 0 ? lineText : ' ',
      startTime: Number(line?.startTime ?? 0),
      endTime: Number(line?.endTime ?? line?.startTime ?? 0),
    },
  ]
}

function normalizeLyricLines(lines) {
  if (!Array.isArray(lines)) return []

  return lines.map((line) => {
    const words = toWordEntries(line)
    const wordStart = words.length > 0 ? words[0].startTime : Number(line?.startTime ?? 0)
    const wordEnd = words.length > 0 ? words[words.length - 1].endTime : Number(line?.endTime ?? wordStart)
    const startTime = Number(line?.startTime ?? wordStart)
    const endTime = Number(line?.endTime ?? wordEnd)

    return {
      words,
      translatedLyric: String(line?.translatedLyric ?? ''),
      romanLyric: String(line?.romanLyric ?? ''),
      startTime: Number.isFinite(startTime) ? startTime : 0,
      endTime: Math.max(Number.isFinite(startTime) ? startTime : 0, Number.isFinite(endTime) ? endTime : 0),
      isBG: Boolean(line?.isBG),
      isDuet: Boolean(line?.isDuet),
    }
  })
}

const state = {
  lyricLines: [],
  currentTime: 0,
  isSeeking: false,
}

let player = null
let rafId = null
let lastFrameTime = -1
let backgroundRender = null
let lastAlbumArt = ''
let currentProfile = { ...QUALITY_PROFILE }
let currentBackgroundProfile = { ...DEFAULT_BG_PROFILE }
let lastIncomingTime = null
let seekUntilTs = 0

function callBackground(methodName, ...args) {
  if (!backgroundRender || typeof backgroundRender[methodName] !== 'function') return
  backgroundRender[methodName](...args)
}

function getBackgroundRendererCtor(mode) {
  return String(mode ?? '').toLowerCase() === 'mesh' ? MeshGradientRenderer : PixiRenderer
}

function applyBackgroundProfile(profile) {
  currentBackgroundProfile = {
    ...currentBackgroundProfile,
    ...profile,
  }
  callBackground('setFPS', Number(currentBackgroundProfile.fps || 60))
  callBackground('setFlowSpeed', Number(currentBackgroundProfile.flowSpeed || 2.2))
  callBackground('setRenderScale', Number(currentBackgroundProfile.renderScale || 0.8))
  callBackground('setStaticMode', Boolean(currentBackgroundProfile.staticMode))
  callBackground('setLowFreqVolume', Number(currentBackgroundProfile.lowFreqVolume || 1))
  callBackground('setHasLyric', Boolean(currentBackgroundProfile.hasLyric))
  callBackground('resume')
}

function rebuildBackgroundRender() {
  const app = document.getElementById('app')
  if (!app) return

  if (backgroundRender) {
    backgroundRender.dispose()
    backgroundRender = null
  }

  backgroundRender = BackgroundRender.new(getBackgroundRendererCtor(currentBackgroundProfile.renderer))
  const bgElement = backgroundRender.getElement()
  bgElement.style.position = 'absolute'
  bgElement.style.inset = '0'
  bgElement.style.width = '100%'
  bgElement.style.height = '100%'
  bgElement.style.zIndex = '0'
  app.prepend(bgElement)

  applyBackgroundProfile(currentBackgroundProfile)
  if (lastAlbumArt) {
    callBackground('setAlbum', lastAlbumArt)
  }
}

function callPlayer(methodName, ...args) {
  if (!player || typeof player[methodName] !== 'function') return
  player[methodName](...args)
}

function applyMotionProfile(profile) {
  currentProfile = { ...profile }
  callPlayer('setAlignAnchor', currentProfile.alignAnchor)
  callPlayer('setAlignPosition', currentProfile.alignPosition)
  callPlayer('setEnableSpring', currentProfile.enableSpring)
  callPlayer('setEnableScale', currentProfile.enableScale)
  callPlayer('setEnableBlur', currentProfile.enableBlur)
  callPlayer('setHidePassedLines', currentProfile.hidePassedLines)
  callPlayer('setWordFadeWidth', currentProfile.wordFadeWidth)
  callPlayer('setLinePosYSpringParams', currentProfile.linePosYSpringParams)
  callPlayer('setLineScaleSpringParams', currentProfile.lineScaleSpringParams)
}

function markSeeking(now) {
  state.isSeeking = true
  seekUntilTs = now + SEEK_HOLD_MS
  callPlayer('setIsSeeking', true)
}

function updateSeekingStateFromTime(now, nextTimeMs) {
  if (!Number.isFinite(nextTimeMs)) return

  if (lastIncomingTime == null) {
    lastIncomingTime = nextTimeMs
    return
  }

  const diff = Math.abs(nextTimeMs - lastIncomingTime)
  lastIncomingTime = nextTimeMs
  if (diff >= SEEK_THRESHOLD_MS) {
    markSeeking(now)
  }
}

function settleSeekingIfNeeded(now) {
  if (!state.isSeeking) return
  if (now < seekUntilTs) return
  state.isSeeking = false
  callPlayer('setIsSeeking', false)
}

function applyPlayerStyle(element) {
  element.style.width = '100%'
  element.style.height = '100%'
  element.style.background = PLAYER_BACKGROUND
  element.style.mixBlendMode = 'plus-lighter'
  element.style.color = '#f5f7ff'
  element.style.fontFamily = '"SF Pro Display", "PingFang SC", system-ui, -apple-system, "Segoe UI", sans-serif'
  element.style.setProperty('--amll-lp-color', '#f5f7ff')
  element.style.setProperty('--amll-lp-bg-color', 'rgba(0, 0, 0, 0.28)')
  element.style.setProperty('--amll-lp-hover-bg-color', 'rgba(255, 255, 255, 0.12)')
  element.style.setProperty('--amll-lp-font-size', 'clamp(20px, 5.2vh, 52px)')
}

function animationFrameLoop() {
  if (!player) return

  try {
    const now = performance.now()
    const delta = lastFrameTime === -1 ? 0 : now - lastFrameTime
    lastFrameTime = now

    settleSeekingIfNeeded(now)
    callPlayer('setCurrentTime', Math.trunc(state.currentTime), state.isSeeking)
    callPlayer('update', delta)
  } catch (error) {
    logToAndroid(`[AMLL-ERROR] update loop error: ${error?.message || error}`)
  }

  rafId = window.requestAnimationFrame(animationFrameLoop)
}

function startAnimationLoop() {
  if (rafId != null) {
    window.cancelAnimationFrame(rafId)
    rafId = null
  }
  lastFrameTime = -1
  rafId = window.requestAnimationFrame(animationFrameLoop)
}

function mountPlayer() {
  const app = document.getElementById('app')
  if (!app) {
    logToAndroid('[AMLL-ERROR] #app container not found')
    return
  }

  app.innerHTML = ''
  app.style.background = PLAYER_BACKGROUND

  // 重置专辑图缓存，确保新的 backgroundRender 实例会重新加载专辑图
  lastAlbumArt = ''

  try {
    rebuildBackgroundRender()

    player = new LyricPlayer()
    const playerElement = player.getElement()
    applyPlayerStyle(playerElement)
    playerElement.style.position = 'absolute'
    playerElement.style.inset = '0'
    playerElement.style.zIndex = '1'
    app.appendChild(playerElement)

    logToAndroid(`[AMLL-INIT] Core LyricPlayer created, container width=${app.clientWidth}, height=${app.clientHeight}`)

    applyMotionProfile(QUALITY_PROFILE)
    if (typeof player.addEventListener === 'function') {
      player.addEventListener('line-click', (evt) => {
        try {
          const lineIndex = Number(evt?.lineIndex ?? -1)
          const startTime = Math.trunc(Number(evt?.line?.getLine?.()?.startTime ?? 0))
          if (typeof Android !== 'undefined' && Android?.onLineClick) {
            Android.onLineClick(lineIndex, startTime)
          }
        } catch (error) {
          logToAndroid(`[AMLL-ERROR] line-click bridge error: ${error?.message || error}`)
        }
      })
    }

    callPlayer('setLyricLines', [])
    callPlayer('setCurrentTime', 0, false)
    callPlayer('update', 0)
    applyBackgroundProfile({ hasLyric: false })
    startAnimationLoop()

    logToAndroid('[AMLL-INIT] Core LyricPlayer mounted, ready for lyrics')
  } catch (error) {
    logToAndroid(`[AMLL-ERROR] Failed to create LyricPlayer: ${error?.message || error}`)
  }
}

window.setRenderMode = function (mode) {
  const normalizedMode = String(mode ?? '').toLowerCase()
  if (normalizedMode === 'dom-lite') {
    applyMotionProfile(LITE_PROFILE)
    logToAndroid('[AMLL-CALL] setRenderMode(dom-lite) -> lite profile applied')
    return
  }

  applyMotionProfile(QUALITY_PROFILE)
  logToAndroid(`[AMLL-CALL] setRenderMode(${mode}) -> quality profile applied`)
}

window.updateLyrics = function (lyricsPayload) {
  try {
    const rawLines = Array.isArray(lyricsPayload?.lines) ? lyricsPayload.lines : []
    state.lyricLines = normalizeLyricLines(rawLines)

    if (player) {
      callPlayer('setLyricLines', state.lyricLines, Math.trunc(state.currentTime))
      callPlayer('setCurrentTime', Math.trunc(state.currentTime), true)
      callPlayer('update', 0)
      logToAndroid(`[AMLL-SUCCESS] Updated player with ${state.lyricLines.length} lines`)
    }
    if (backgroundRender) {
      applyBackgroundProfile({ hasLyric: state.lyricLines.length > 0 })
    }

    logToAndroid(`[AMLL-SUCCESS] Updated lyrics (${state.lyricLines.length} lines)`)
  } catch (error) {
    logToAndroid(`[AMLL-ERROR] updateLyrics error: ${error?.message || error}`)
  }
}

window.updateAlbumArt = async function (albumUri) {
  try {
    const uri = String(albumUri ?? '').trim()
    if (!backgroundRender || uri.length === 0 || uri === lastAlbumArt) return

    await backgroundRender.setAlbum(uri)
    lastAlbumArt = uri
    logToAndroid('[AMLL-SUCCESS] Background album art updated')
  } catch (error) {
    logToAndroid(`[AMLL-ERROR] updateAlbumArt error: ${error?.message || error}`)
  }
}

window.updateTime = function (timeMs) {
  const now = performance.now()
  const parsedTime = Number(timeMs)
  state.currentTime = Number.isFinite(parsedTime) ? parsedTime : 0
  updateSeekingStateFromTime(now, state.currentTime)
}

window.configureLyricMotion = function (options) {
  if (!options || typeof options !== 'object') return
  const merged = {
    ...currentProfile,
    ...options,
    linePosYSpringParams: {
      ...currentProfile.linePosYSpringParams,
      ...(options.linePosYSpringParams || {}),
    },
    lineScaleSpringParams: {
      ...currentProfile.lineScaleSpringParams,
      ...(options.lineScaleSpringParams || {}),
    },
  }
  applyMotionProfile(merged)
  logToAndroid('[AMLL-CALL] configureLyricMotion applied')
}

window.setBackgroundRenderer = function (mode) {
  const normalized = String(mode ?? '').toLowerCase()
  const renderer = normalized === 'mesh' ? 'mesh' : 'pixi'
  if (renderer === currentBackgroundProfile.renderer && backgroundRender) {
    logToAndroid(`[AMLL-CALL] setBackgroundRenderer(${renderer}) skipped (no change)`)
    return
  }

  currentBackgroundProfile = {
    ...currentBackgroundProfile,
    renderer,
  }
  rebuildBackgroundRender()
  logToAndroid(`[AMLL-CALL] setBackgroundRenderer(${renderer}) applied`)
}

window.updateLowFreqVolume = function (value) {
  const parsed = Number(value)
  const clamped = Number.isFinite(parsed) ? Math.max(0, Math.min(1, parsed)) : 1
  applyBackgroundProfile({ lowFreqVolume: clamped })
}

window.configureBackgroundEffect = function (options) {
  if (!options || typeof options !== 'object') return

  const next = {
    ...currentBackgroundProfile,
    ...options,
  }
  if (typeof next.renderer === 'string') {
    next.renderer = next.renderer.toLowerCase() === 'mesh' ? 'mesh' : 'pixi'
  } else {
    next.renderer = currentBackgroundProfile.renderer
  }

  const rendererChanged = next.renderer !== currentBackgroundProfile.renderer
  currentBackgroundProfile = next

  if (rendererChanged) {
    rebuildBackgroundRender()
  } else {
    applyBackgroundProfile(currentBackgroundProfile)
  }
  logToAndroid('[AMLL-CALL] configureBackgroundEffect applied')
}

window.logFromKotlin = function (message) {
  logToAndroid(`[JS] ${message}`)
}

window.addEventListener('DOMContentLoaded', () => {
  document.documentElement.style.background = 'transparent'
  document.body.style.background = 'transparent'
  mountPlayer()
})

window.addEventListener('beforeunload', () => {
  if (rafId != null) {
    window.cancelAnimationFrame(rafId)
    rafId = null
  }
  if (backgroundRender) {
    backgroundRender.dispose()
    backgroundRender = null
  }
})
