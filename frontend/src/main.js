import { LyricPlayer, BackgroundRender, PixiRenderer } from '@applemusic-like-lyrics/core'
import '@applemusic-like-lyrics/core/style.css'

const PLAYER_BACKGROUND = 'transparent'

function logToAndroid(message) {
  if (typeof Android !== 'undefined' && Android?.log) {
    Android.log(message)
  }
}

function toWordEntries(line) {
  if (Array.isArray(line?.words) && line.words.length > 0) {
    return line.words.map((word) => ({
      word: String(word?.word ?? ''),
      startTime: Number(word?.startTime ?? line?.startTime ?? 0),
      endTime: Number(word?.endTime ?? line?.endTime ?? line?.startTime ?? 0),
    }))
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

  return lines.map((line) => ({
    words: toWordEntries(line),
    translatedLyric: String(line?.translatedLyric ?? ''),
    romanLyric: String(line?.romanLyric ?? ''),
    startTime: Number(line?.startTime ?? 0),
    endTime: Number(line?.endTime ?? line?.startTime ?? 0),
    isBG: Boolean(line?.isBG),
    isDuet: Boolean(line?.isDuet),
  }))
}

const state = {
  lyricLines: [],
  currentTime: 0,
}

let player = null
let rafId = null
let lastFrameTime = -1
let backgroundRender = null
let lastAlbumArt = ''
let blurRestoreTimer = null
const BLUR_RESTORE_IDLE_MS = 5000

function setLyricBlurEnabled(enabled) {
  if (player && typeof player.setEnableBlur === 'function') {
    player.setEnableBlur(Boolean(enabled))
  }
}

function onUserLyricsInteraction() {
  setLyricBlurEnabled(false)

  if (blurRestoreTimer != null) {
    window.clearTimeout(blurRestoreTimer)
    blurRestoreTimer = null
  }

  blurRestoreTimer = window.setTimeout(() => {
    setLyricBlurEnabled(true)
    blurRestoreTimer = null
  }, BLUR_RESTORE_IDLE_MS)
}

function bindInteractionBlurControl(targetElement) {
  if (!targetElement) return

  const events = [
    'touchstart',
    'touchmove',
    'touchend',
    'pointerdown',
    'pointermove',
    'pointerup',
    'wheel',
  ]

  events.forEach((eventName) => {
    targetElement.addEventListener(eventName, onUserLyricsInteraction, { passive: true })
  })
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

    player.setCurrentTime(Math.trunc(state.currentTime))
    player.update(delta)
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
    backgroundRender = BackgroundRender.new(PixiRenderer)
    const bgElement = backgroundRender.getElement()
    bgElement.style.position = 'absolute'
    bgElement.style.inset = '0'
    bgElement.style.width = '100%'
    bgElement.style.height = '100%'
    bgElement.style.zIndex = '0'
    app.appendChild(bgElement)

    backgroundRender.setRenderScale(0.6)

    player = new LyricPlayer()
    const playerElement = player.getElement()
    applyPlayerStyle(playerElement)
    playerElement.style.position = 'absolute'
    playerElement.style.inset = '0'
    playerElement.style.zIndex = '1'
    app.appendChild(playerElement)

    logToAndroid(`[AMLL-INIT] Core LyricPlayer created, container width=${app.clientWidth}, height=${app.clientHeight}`)

    if (typeof player.setEnableSpring === 'function') player.setEnableSpring(true)
    setLyricBlurEnabled(true)
    if (typeof player.setEnableScale === 'function') player.setEnableScale(true)
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

    player.setLyricLines([])
    player.setCurrentTime(0)
    bindInteractionBlurControl(playerElement)
    bindInteractionBlurControl(app)
    startAnimationLoop()

    logToAndroid('[AMLL-INIT] Core LyricPlayer mounted, ready for lyrics')
  } catch (error) {
    logToAndroid(`[AMLL-ERROR] Failed to create LyricPlayer: ${error?.message || error}`)
  }
}

window.setRenderMode = function (mode) {
  logToAndroid(`[AMLL-CALL] setRenderMode(${mode}) ignored (core mode)`)
}

window.updateLyrics = function (lyricsPayload) {
  try {
    const rawLines = Array.isArray(lyricsPayload?.lines) ? lyricsPayload.lines : []
    state.lyricLines = normalizeLyricLines(rawLines)

    if (player) {
      player.setLyricLines(state.lyricLines)
      player.update(0)
      logToAndroid(`[AMLL-SUCCESS] Updated player with ${state.lyricLines.length} lines`)
    }
    if (backgroundRender) {
      backgroundRender.setHasLyric(state.lyricLines.length > 0)
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
  const parsedTime = Number(timeMs)
  state.currentTime = Number.isFinite(parsedTime) ? parsedTime : 0
  if (player) {
    player.setCurrentTime(Math.trunc(state.currentTime))
  }
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
  if (blurRestoreTimer != null) {
    window.clearTimeout(blurRestoreTimer)
    blurRestoreTimer = null
  }
  if (backgroundRender) {
    backgroundRender.dispose()
    backgroundRender = null
  }
})
