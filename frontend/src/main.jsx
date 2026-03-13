import React, { useState } from 'react'
import ReactDOM from 'react-dom'
import { LyricPlayer, BackgroundRender, PixiRenderer, MeshGradientRenderer } from '@applemusic-like-lyrics/core'
import '@applemusic-like-lyrics/core/style.css'

// --- existing constants unchanged ---
const PLAYER_BACKGROUND = 'transparent'
const SEEK_THRESHOLD_MS = 900
const SEEK_HOLD_MS = 180
const DEFAULT_FONT_STACK = '"SF Pro Display", "PingFang SC", system-ui, -apple-system, "Segoe UI", sans-serif'
const DYNAMIC_FONT_STYLE_ID = 'amll-dynamic-font-face-style'

const QUALITY_PROFILE = {
  alignAnchor: 'center',
  // see comments in main.js: compensate for half‑screen blank area
  alignPosition: -0.1,
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

const TOUCH_BG_BLUR_CLASS = 'amll-touch-unblur'

// --- shared state & globals (moved to top to avoid TDZ errors) ---
let state = {
  lyricLines: [],
  currentTime: 0,
  isPaused: false, // track whether playback is currently paused
  isSeeking: false,
  blur: {
    enabled: true,
    timeoutId: null,
    TIMEOUT_MS: 5000, // 5秒
  },
  touch: {
    startX: 0,
    startY: 0,
    startTime: 0,
    isMoved: false,
  },
}

// insert a small stylesheet rule that lets us quickly un‑blur a single
// line by adding the `amll-line-unblur` class. the core library already
// applies per-line blur but this gives us a way to override a touched
// line without turning off blur for the entire player.
const UNBLUR_STYLE_ID = 'amll-unblur-style'
function ensureUnblurStyle() {
  if (document.getElementById(UNBLUR_STYLE_ID)) return
  const s = document.createElement('style')
  s.id = UNBLUR_STYLE_ID
  s.textContent = `[class*="_lyricLine_"] .amll-line-unblur, [class*="_lyricLine_"].amll-line-unblur {
  filter: none !important;
}`
  document.head.appendChild(s)
}

// when playback is paused the library still leaves CSS animations running
// on the currently active line, which makes the text look like it's still
// moving even though the rest of the player has frozen. we inject a
// stylesheet that sets `animation-play-state: paused` and zeroes out
// transition durations whenever the root player element carries the
// `amll-paused` class. the class is toggled from `updateTime` below.
//
// previously we only targeted the *main* line so background or duet
// lyrics continued animating; pause should freeze whatever line is
// currently active, regardless of its semantic role.
const PAUSE_STYLE_ID = 'amll-pause-style'
function ensurePauseStyle() {
  if (document.getElementById(PAUSE_STYLE_ID)) return
  const s = document.createElement('style')
  s.id = PAUSE_STYLE_ID
  s.textContent = `
/* freeze any active lyric line when paused (main / background / duet) */
.amll-lyric-player.amll-paused [class*="_lyricLine_"][class*="_active_"] *,
.amll-lyric-player.amll-paused [class*="_lyricLine_"][class*="_active_"] ._romanWord_*,
.amll-lyric-player.amll-paused [class*="_lyricLine_"][class*="_active_"] ._emphasizeWrapper_*,
/* also prevent any span under the active line from transforming */
.amll-lyric-player.amll-paused [class*="_lyricLine_"][class*="_active_"] span {
  animation-play-state: paused !important;
  transition-duration: 0s !important;
  transform: none !important;
  /* prevent mask-* properties from animating when we rely on inline
     styles to freeze them above */
  mask-size: inherit !important;
  -webkit-mask-size: inherit !important;
}
`
  document.head.appendChild(s)
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

// mirror important state on window so callbacks in the bundle can access them
window.__amll = window.__amll || {}
Object.assign(window.__amll, {
  get player() { return player }, set player(v) { player = v },
  get rafId() { return rafId }, set rafId(v) { rafId = v },
  get lastFrameTime() { return lastFrameTime }, set lastFrameTime(v) { lastFrameTime = v },
  get backgroundRender() { return backgroundRender }, set backgroundRender(v) { backgroundRender = v },
  get lastAlbumArt() { return lastAlbumArt }, set lastAlbumArt(v) { lastAlbumArt = v },
  get currentProfile() { return currentProfile }, set currentProfile(v) { currentProfile = v },
  get currentBackgroundProfile() { return currentBackgroundProfile }, set currentBackgroundProfile(v) { currentBackgroundProfile = v },
  get state() { return state }, set state(v) { state = v },
})

function amllGet(name){return window.__amll ? window.__amll[name] : undefined}

// helper to write to the shared global state object
function amllSet(name, value) {
  if (!window.__amll) window.__amll = {}
  window.__amll[name] = value
  return value
}


function logToAndroid(message) {
  if (typeof Android !== 'undefined' && Android?.log) {
    Android.log(message)
  }
}

function stripLeadingBgBracket(text) {
  return String(text ?? '').replace(/^\s*[\(（]\s*/, '')
}

function stripTrailingBgBracket(text) {
  return String(text ?? '').replace(/\s*[\)）]\s*$/, '')
}

function toWordEntries(line) {
  if (Array.isArray(line?.words) && line.words.length > 0) {
    const mapped = line.words.map((word) => ({
      word: String(word?.word ?? ''),
      startTime: Number(word?.startTime ?? line?.startTime ?? 0),
      endTime: Number(word?.endTime ?? line?.endTime ?? line?.startTime ?? 0),
    }))

    const normalized = mapped.map((word) => {
      const startTime = Number.isFinite(word.startTime) ? word.startTime : 0
      const endTime = Number.isFinite(word.endTime) ? word.endTime : startTime
      return {
        ...word,
        startTime,
        endTime: Math.max(startTime, endTime),
      }
    })

    // 背景歌词：去除第一个词开头的'('和最后一个词结尾的')'
    if (line?.isBG && normalized.length > 0) {
      logToAndroid(`[BG-LYRICS-DEBUG] Processing background lyrics with ${normalized.length} words`)
      
      // 去除第一个词的开头括号
      const firstWord = normalized[0]
      const originalFirst = firstWord.word
      firstWord.word = stripLeadingBgBracket(firstWord.word)
      if (firstWord.word !== originalFirst) {
        logToAndroid(`[BG-LYRICS-DEBUG] Removed leading bracket from first word: "${originalFirst}" -> "${firstWord.word}"`)
      } else {
        logToAndroid(`[BG-LYRICS-DEBUG] First word unchanged after bracket strip: "${originalFirst}"`)
      }

      // 去除最后一个词的结尾括号
      const lastWord = normalized[normalized.length - 1]
      const originalLast = lastWord.word
      lastWord.word = stripTrailingBgBracket(lastWord.word)
      if (lastWord.word !== originalLast) {
        logToAndroid(`[BG-LYRICS-DEBUG] Removed trailing bracket from last word: "${originalLast}" -> "${lastWord.word}"`)
      } else {
        logToAndroid(`[BG-LYRICS-DEBUG] Last word unchanged after bracket strip: "${originalLast}"`)
      }

      const afterText = normalized.map((w) => w.word).join('')
      logToAndroid(`[BG-LYRICS-DEBUG] BG words after strip: "${afterText}"`)

      for (let i = normalized.length - 1; i >= 0; i -= 1) {
        if (String(normalized[i].word ?? '').length === 0) {
          normalized.splice(i, 1)
        }
      }

      if (normalized.length === 0) {
        normalized.push({
          word: ' ',
          startTime: Number(line?.startTime ?? 0),
          endTime: Number(line?.endTime ?? line?.startTime ?? 0),
        })
      }
    }

    return normalized
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

// ensure function visible globally for older ff references
window.toWordEntries = toWordEntries

// normalizeLyricLines originally lived in main.js; the React entrypoint
// didn't include it, resulting in a runtime reference error when
// updateLyrics invoked it.  Define it here so bundler will package it.
function normalizeLyricLines(lines) {
  if (!Array.isArray(lines)) return []

  return lines.map((line) => {
    const words = toWordEntries(line)
    const wordStart = words.length > 0 ? words[0].startTime : Number(line?.startTime ?? 0)
    const wordEnd = words.length > 0 ? words[words.length - 1].endTime : Number(line?.endTime ?? wordStart)
    const startTime = Number(line?.startTime ?? wordStart)
    const endTime = Number(line?.endTime ?? wordEnd)

    const result = {
      words,
      translatedLyric: String(line?.translatedLyric ?? ''),
      romanLyric: String(line?.romanLyric ?? ''),
      startTime: Number.isFinite(startTime) ? startTime : 0,
      endTime: Number.isFinite(endTime) ? endTime : 0,
      isBG: !!line?.isBG,
      isDuet: !!line?.isDuet,
    }

    // fallback to word-level timings if the computed values are invalid
    if (Number.isFinite(result.startTime) && Number.isFinite(result.endTime)) {
      return result
    }

    return {
      words: toWordEntries(line),
      translatedLyric: String(line?.translatedLyric ?? ''),
      romanLyric: String(line?.romanLyric ?? ''),
      startTime: Number.isFinite(result.startTime) ? result.startTime : 0,
      endTime: Number.isFinite(result.endTime) ? result.endTime : 0,
      isBG: !!line?.isBG,
      isDuet: !!line?.isDuet,
    }
  })
}

function callBackground(methodName, ...args) {
  const br = amllGet('backgroundRender') || backgroundRender
  if (!br || typeof br[methodName] !== 'function') return
  br[methodName](...args)
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
  // render should sit behind lyrics and stay locked to the viewport, not
  // scroll with the document
  bgElement.style.position = 'fixed'
  bgElement.style.top = '0'
  bgElement.style.left = '0'
  bgElement.style.width = '100%'
  bgElement.style.height = '100%'
  bgElement.style.zIndex = '0'
  // insert before #app's other children so lyrics (z-index 1) float above
  app.prepend(bgElement)

  applyBackgroundProfile(currentBackgroundProfile)
  const art = amllGet('lastAlbumArt') || lastAlbumArt
  if (art) {
    callBackground('setAlbum', art)
  }
}

function callPlayer(methodName, ...args) {
  const pl = amllGet('player') || player
  if (!pl || typeof pl[methodName] !== 'function') return
  pl[methodName](...args)
}

function applyMotionProfile(profile) {
  currentProfile = { ...profile }
  if (window.__amll) window.__amll.currentProfile = currentProfile
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

function resetBlurTimeout() {
  // 清除旧的计时器
  if (state.blur.timeoutId !== null) {
    clearTimeout(state.blur.timeoutId)
  }
  
  // 设置新的计时器，5秒后恢复模糊
  state.blur.timeoutId = setTimeout(() => {
    if (player && state.blur.enabled === false) {
      callPlayer('setEnableBlur', true)
      state.blur.enabled = true
      player.getElement?.().classList.remove(TOUCH_BG_BLUR_CLASS)
      logToAndroid('[AMLL-BLUR] Blur restored after 5s inactivity')

      // also cleanup any lingering line-specific overrides
      document.querySelectorAll('.amll-line-unblur').forEach(el => el.classList.remove('amll-line-unblur'))
    }
    state.blur.timeoutId = null
  }, state.blur.TIMEOUT_MS)
}

function handleTouchStart(e) {
  // 记录触摸位置和时间
  const touch = e?.touches?.[0]
  state.touch.startX = touch?.clientX ?? 0
  state.touch.startY = touch?.clientY ?? 0
  state.touch.startTime = Date.now()
  state.touch.isMoved = false

  // 保持主歌词原有体验：触摸时取消模糊
  if (player && state.blur.enabled === true) {
    callPlayer('setEnableBlur', false)
    state.blur.enabled = false
    player.getElement?.().classList.add(TOUCH_BG_BLUR_CLASS)
    logToAndroid('[AMLL-BLUR] Blur disabled on touch, keep BG blurred')
  }

  // also unblur just the line under the finger so that the user can
  // tap a lyric and see it clearly without removing blur from every line.
  try {
    const x = touch?.clientX ?? state.touch.startX
    const y = touch?.clientY ?? state.touch.startY
    const el = document.elementFromPoint(x, y)
    const lineEl = el?.closest ? el.closest('[class*="_lyricLine_"]') : null
    if (lineEl) {
      lineEl.classList.add('amll-line-unblur')
    }
  } catch (_ignored) {}

  resetBlurTimeout()
}

function handleTouchMove(e) {
  // 检测是否有显著移动 (大于10像素)
  const moveX = Math.abs((e?.touches?.[0]?.clientX ?? 0) - state.touch.startX)
  const moveY = Math.abs((e?.touches?.[0]?.clientY ?? 0) - state.touch.startY)
  
  if (moveX > 10 || moveY > 10) {
    state.touch.isMoved = true
  }

  if (state.blur.timeoutId !== null) {
    clearTimeout(state.blur.timeoutId)
  }
  resetBlurTimeout()
}

function handleTouchEnd(e) {
  const touchDuration = Date.now() - state.touch.startTime
  
  // 如果是快速短按（<300ms）且没有明显移动，视为点击
  if (!state.touch.isMoved && touchDuration < 300) {
    const x = e?.changedTouches?.[0]?.clientX ?? state.touch.startX
    const y = e?.changedTouches?.[0]?.clientY ?? state.touch.startY
    
    logToAndroid(`[AMLL-TAP] Tap detected at coordinates (${x}, ${y}), duration=${touchDuration}ms`)
    
    // 模拟点击事件
    try {
      const element = document.elementFromPoint(x, y)
      if (element) {
        logToAndroid(`[AMLL-TAP] Clicked element: ${element.tagName}, class=${element.className}`)
        
        // 尝试在其最近的歌词行容器上触发点击
        let lyricLine = element.closest('._lyricLine_1vq69_6, ._lyricLine_1ygrf_6')
        if (!lyricLine) {
          lyricLine = element.closest('[class*="lyric"]')
        }
        
        if (lyricLine) {
          logToAndroid(`[AMLL-TAP] Found lyric line element`)
          lyricLine.click?.()
          
          // 如果无法通过该方法触发，尝试手动分发click事件
          const clickEvent = new MouseEvent('click', {
            bubbles: true,
            cancelable: true,
            view: window
          })
          lyricLine.dispatchEvent(clickEvent)
          logToAndroid(`[AMLL-TAP] Dispatched click event`)

          // always reload after a user tap; this restarts the animation on
          // whichever line ends up being active (current line or the one
          // the player jumped to), fulfilling the “replay current line”
          // requirement.
          setTimeout(reloadCurrentLine, 50)
        }
      }
    } catch (error) {
      logToAndroid(`[AMLL-TAP-ERROR] ${error?.message || error}`)
    }
  }

  // remove any temporary per-line unblur classes now that the touch has ended
  document.querySelectorAll('.amll-line-unblur').forEach(el => el.classList.remove('amll-line-unblur'))

  resetBlurTimeout()
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
  // allow the container to size itself vertically rather than forcing a
  // fixed height. lines will be left in the normal flow and no translation
  // is applied, allowing the page scrollbar to move them directly.
  element.style.height = 'auto'
  element.style.overflowY = 'auto'
  element.style.maxHeight = '100vh'

  // because the library's base stylesheet forces lyric lines to be
  // absolutely positioned, we inject a small override rule here. that
  // shifts every `._lyricLine_…` element back into the normal document
  // flow so scrolling is courtesy of the page rather than manual transforms.
  if (!document.getElementById('amll-flow-override')) {
    const rule = `.amll-lyric-player [class*="_lyricLine_"] { position: static !important; transform: none !important; margin: 0 !important; }`;
    const styleTag = document.createElement('style')
    styleTag.id = 'amll-flow-override'
    styleTag.textContent = rule
    document.head.appendChild(styleTag)
  }

  element.style.background = PLAYER_BACKGROUND
  // avoid blend mode which may cancel out lyrics against album art
  element.style.mixBlendMode = 'normal'
  element.style.color = '#f5f7ff'
  element.style.setProperty('--amll-lp-font-family', `var(--amll-user-font-family, ${DEFAULT_FONT_STACK})`)
  element.style.fontFamily = 'var(--amll-lp-font-family)'
  element.style.fontWeight = '700'
  element.style.setProperty('--amll-lp-color', '#f5f7ff')
  element.style.setProperty('--amll-lp-bg-color', 'rgba(0, 0, 0, 0.28)')
  element.style.setProperty('--amll-lp-hover-bg-color', 'rgba(255, 255, 255, 0.12)')
  element.style.setProperty('--amll-lp-font-size', 'clamp(30px, 4vh, 36px)')

  // 触摸导致全局去模糊时，仅对背景歌词加回固定模糊，主歌词保持清晰
  element.style.setProperty('--amll-touch-bg-blur', '10px')
}

function escapeCssString(value) {
  return String(value ?? '').replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

function setFontSettings(fontFamily, activeFontFamilyNames = [], fontFiles = []) {
  const fallbackFamily = String(fontFamily || DEFAULT_FONT_STACK)
  const enabledFamilies = (Array.isArray(activeFontFamilyNames)
    ? activeFontFamilyNames
    : [activeFontFamilyNames]
  )
    .map((name) => String(name || '').trim())
    .filter((name) => name.length > 0)
    .sort((a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' }))

  const effectiveFamily = enabledFamilies.length > 0
    ? `${enabledFamilies.map((name) => `"${name}"`).join(', ')}, ${fallbackFamily}`
    : fallbackFamily

  let styleTag = document.getElementById(DYNAMIC_FONT_STYLE_ID)
  if (!styleTag) {
    styleTag = document.createElement('style')
    styleTag.id = DYNAMIC_FONT_STYLE_ID
    document.head.appendChild(styleTag)
  }

  const css = (Array.isArray(fontFiles) ? fontFiles : [])
    .filter((item) => item && item.familyName && item.uri)
    .map((item) => `@font-face{font-family:"${escapeCssString(item.familyName)}";src:url("${escapeCssString(item.uri)}");font-display:swap;}`)
    .join('')
  styleTag.textContent = css

  document.documentElement.style.setProperty('--amll-user-font-family', effectiveFamily)
  document.documentElement.style.setProperty('--amll-lp-font-family', 'var(--amll-user-font-family)')

  if (player) {
    const el = player.getElement?.()
    if (el) {
      el.style.setProperty('--amll-lp-font-family', 'var(--amll-user-font-family)')
      el.style.fontFamily = 'var(--amll-lp-font-family)'
    }
  }
}

// visibility logging helps detect if the WebView/page is being
// backgrounded or paused by the host. record initial state and
// subsequent changes to help diagnose timer throttling or pause
// behavior that can interfere with DOM updates.
function initVisibilityLogging() {
  try {
    logToAndroid(`[AMLL-VIS] initial visibility=${document.visibilityState} hidden=${document.hidden}`)
    document.addEventListener('visibilitychange', () => {
      try {
        logToAndroid(`[AMLL-VIS] visibilitychange => ${document.visibilityState} hidden=${document.hidden}`)
      } catch (_e) {}
    })
    window.addEventListener('pagehide', () => {
      try { logToAndroid('[AMLL-VIS] pagehide') } catch (_e) {}
    })
    window.addEventListener('pageshow', () => {
      try { logToAndroid('[AMLL-VIS] pageshow') } catch (_e) {}
    })
  } catch (_e) {}
}

function animationFrameLoop() {
  if (!player) return

  try {
    const now = performance.now()
    const delta = lastFrameTime === -1 ? 0 : now - lastFrameTime
    lastFrameTime = now

    // if paused we still want the RAF ticking so we can detect resume,
    // but avoid calling into the player to move any animations.
    if (state.isPaused) {
      rafId = window.requestAnimationFrame(animationFrameLoop)
      return
    }

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

  // make sure the document is allowed to scroll vertically; some
  // embed hosts might set overflow hidden by default
  document.documentElement.style.overflowY = 'auto'
  document.body.style.overflowY = 'auto'

  app.innerHTML = ''
  app.style.background = PLAYER_BACKGROUND

  // 重置专辑图缓存，确保新的 backgroundRender 实例会重新加载专辑图
  amllSet('lastAlbumArt', '')
  lastAlbumArt = ''

  try {
    // make sure the helper stylesheets exist before we start
    ensureUnblurStyle()
    ensurePauseStyle()

    rebuildBackgroundRender()

    player = new LyricPlayer()

    // keep every line in the DOM: the library normally prunes off-screen
    // lines using `bufferedLines`/`hotLines`. by keeping those sets full we
    // ensure the DOM never loses lines (so scroll can reach start/end).
    function fillAllLineIndexes() {
      if (!this.currentLyricLines || !Array.isArray(this.currentLyricLines)) return
      const len = this.currentLyricLines.length
      if (!(this.bufferedLines instanceof Set)) this.bufferedLines = new Set()
      if (!(this.hotLines instanceof Set)) this.hotLines = new Set()
      this.bufferedLines.clear()
      this.hotLines.clear()
      for (let i = 0; i < len; i++) {
        this.bufferedLines.add(i)
        this.hotLines.add(i)
      }
    }

    const originalSetLyricLines = player.setLyricLines.bind(player)
    player.setLyricLines = function (lines, time = 0) {
      originalSetLyricLines(lines, time)
      fillAllLineIndexes.call(this)
    }

    const originalSetCurrentTime = player.setCurrentTime.bind(player)
    player.setCurrentTime = function (time, seeking = false) {
      originalSetCurrentTime(time, seeking)
      fillAllLineIndexes.call(this)
    }

    // revert to default scrolling behaviour: keep players lines in DOM flow
    // instead of clamping height and translating them manually.
    player.allowScroll = true

    // keep the library's internal scroll handler logic for offset tracking
    // but avoid `preventDefault` so native scrolling is allowed.
    player.beginScrollHandler = function() {
      const e = this.allowScroll;
      if (e) {
        this.isScrolled = true;
        clearTimeout(this.scrolledHandler);
        this.scrolledHandler = setTimeout(() => {
          this.isScrolled = false;
          this.scrollOffset = 0;
        }, 5000);
      }
      return false; // do NOT intercept the event
    }

    // after each layout run the library assigns transforms to each line. we
    // clear them so lines stay in their natural document position.
    // override calcLayout completely: we don't want the library to
    // wrap the original layout so we can drop its spacer/transform logic
    const originalCalcLayout2 = player.calcLayout.bind(player)
    player.calcLayout = async function (animated = false) {
      await originalCalcLayout2(animated)
      // remove the spacer inserted by the original layout
      const sp = document.getElementById('amll-spacer')
      if (sp && sp.parentElement) {
        sp.parentElement.removeChild(sp)
      }
        // clear transforms on every line element (not just currentLyricLineObjects)
        if (this.element) {
          this.element.querySelectorAll('[class*="_lyricLine_"]').forEach(el => {
            el.style.transform = ''
          })
        }
        // don't attempt to set scrollBoundary[1] to an undefined helper value.
        // the core library manages this internally and we just want to keep the
        // DOM in flow rather than rely on its boundary tracking.
        // this.scrollBoundary[1] = max
        // if the library is not currently tracking a user scroll, allow it
        // to drive the scrollTop; otherwise leave the native position alone
        if (!this.isScrolled) {
          this.element.scrollTop = this.scrollOffset
        } else {
          // keep offset in sync with what the user has done
          this.scrollOffset = this.element.scrollTop
        }
    }

    const playerElement = player.getElement()
    applyPlayerStyle(playerElement)

    // disable per-line transforms entirely; they interfere with flow-based
    // layout and contributed to huge gaps when we removed absolute positioning.
    if (player.currentLyricLineObjects && player.currentLyricLineObjects.length > 0) {
      const LineClass = player.currentLyricLineObjects[0].constructor
      if (LineClass && LineClass.prototype) {
        // transforms are unnecessary when we flow the lines normally,
        // disabling avoids conflicts and large gaps
        LineClass.prototype.setTransform = function() {}

        // the core library hides offscreen lines to reduce DOM size,
        // which causes only a handful of elements to be present. for
        // our use-case we want every line to stay in the document so
        // scrolling can reach the very start and end. override the
        // hide() method so it does nothing and keep show() available.
        if (typeof LineClass.prototype.hide === 'function') {
          LineClass.prototype.hide = function() {
            /* no-op: preserve element in DOM */
          }
        }

        // disable the visibility check that normally skips rendering
        // of lines outside the viewport. always return true.
        Object.defineProperty(LineClass.prototype, 'isInSight', {
          get() {
            return true
          }
        })

        // even though hide() is inert, some update loops choose between
        // show/hide based on isInSight; make sure show() always runs.
        if (typeof LineClass.prototype.update === 'function') {
          const orig = LineClass.prototype.update
          LineClass.prototype.update = function(delta = 0) {
            orig.call(this, delta)
            try {
              this.show()
            } catch (_e) {}
          }
        }

        // ensure show still re-attaches if something odd happened
        // (it typically won't be needed, but leaving it in place is
        // harmless).
      }
    }

    // cleanup stray undefined class as before
    if (playerElement.classList.contains('undefined')) {
      playerElement.classList.remove('undefined')
    }

    // ensure a reasonable minimum height so #app doesn't collapse
    playerElement.style.minHeight = '100vh'

    if (player.allowScroll) {
      // non-fixed; it will scroll naturally with document
      playerElement.style.position = 'relative'
    }
    playerElement.style.zIndex = '1'
    app.appendChild(playerElement)

    // touchAction not needed since element no longer covers viewport fully,
    // but leaving pan-y doesn't hurt for occasional fixed children.
    playerElement.style.touchAction = 'pan-y'
    // keep library scrollOffset in sync when user scrolls via scrollbar/keys
    playerElement.addEventListener('scroll', () => {
      player.scrollOffset = playerElement.scrollTop
    })


    logToAndroid(`[AMLL-INIT] Core LyricPlayer created, container width=${app.clientWidth}, height=${app.clientHeight}`)

    applyMotionProfile(QUALITY_PROFILE)
    if (typeof player.addEventListener === 'function') {
      player.addEventListener('line-click', (evt) => {
        try {
          const lineIndex = Number(evt?.lineIndex ?? -1)
          const line = evt?.line
          let startTime = 0
          
          // 尝试多种方式获取 startTime
          if (line && typeof line.getLine === 'function') {
            const lineData = line.getLine()
            startTime = Math.trunc(Number(lineData?.startTime ?? 0))
            logToAndroid(`[AMLL-CLICK] Line ${lineIndex} found via getLine(), startTime=${startTime}ms`)
          } else if (line?.startTime !== undefined) {
            startTime = Math.trunc(Number(line.startTime))
            logToAndroid(`[AMLL-CLICK] Line ${lineIndex} found via direct property, startTime=${startTime}ms`)
          } else if (evt?.startTime !== undefined) {
            startTime = Math.trunc(Number(evt.startTime))
            logToAndroid(`[AMLL-CLICK] Line ${lineIndex} found via evt.startTime, startTime=${startTime}ms`)
          } else {
            logToAndroid(`[AMLL-CLICK] Line ${lineIndex} clicked but startTime not found, using 0`)
          }
          
          if (typeof Android !== 'undefined' && Android?.onLineClick) {
            Android.onLineClick(lineIndex, startTime)
            logToAndroid(`[AMLL-CLICK] ✓ Called Android.onLineClick(${lineIndex}, ${startTime})`)
          } else {
            logToAndroid(`[AMLL-ERROR] Android.onLineClick not available`)
          }
        } catch (error) {
          logToAndroid(`[AMLL-ERROR] line-click handler exception: ${error?.message || error}`)
        }
      })
    }

    // 为app容器添加触摸事件监听器
    app.addEventListener('touchstart', handleTouchStart, false)
    app.addEventListener('touchmove', handleTouchMove, false)
    app.addEventListener('touchend', handleTouchEnd, false)

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

  // always apply a known-quality profile instead of relying on potentially
  // uninitialized currentProfile variable (which might not exist yet when
  // this function is invoked early in initialization)
  applyMotionProfile(QUALITY_PROFILE)
  logToAndroid(`[AMLL-CALL] setRenderMode(${mode}) -> quality profile applied`)
}

window.updateLyrics = function (lyricsPayload) {
  try {
    const rawLines = Array.isArray(lyricsPayload?.lines) ? lyricsPayload.lines : []
    
    // 调试：检查接收到的背景歌词原始数据
    const bgLines = rawLines.filter(line => line?.isBG)
    if (bgLines.length > 0) {
      logToAndroid(`[BG-LYRICS-DEBUG] Received ${bgLines.length} BG lines from backend`)
      bgLines.slice(0, 3).forEach((line, idx) => {
        logToAndroid(`[BG-LYRICS-DEBUG] Raw BG line ${idx}: text="${line?.text}" translation="${line?.translatedLyric}" words=${line?.words?.length || 0}`)
      })
    }
    
    state.lyricLines = normalizeLyricLines(rawLines)

    // Debug: inspect normalized results
    if (state.lyricLines.length > 0) {
      state.lyricLines.slice(0, 3).forEach((ln, idx) => {
        const txt = ln.words.map(w => w.word).join('')
        logToAndroid(`[AMLL-DEBUG] normalized line ${idx}: text="${txt}" len=${ln.words.length}`)
      })
    } else {
      logToAndroid('[AMLL-WARN] normalizeLyricLines produced 0 lines')
    }
    logToAndroid(`[AMLL-DEBUG] lyricsPayload lines count=${rawLines.length}`)

    // fallback when no lines at all
    if (state.lyricLines.length === 0) {
      logToAndroid('[AMLL-DEV] injecting placeholder lyric because none provided')
      state.lyricLines = [
        { words: [{word:'Demo',startTime:0,endTime:2000}],translatedLyric:'',romanLyric:'',startTime:0,endTime:2000,isBG:false,isDuet:false }
      ]
    }

    if (player) {
      let currentTimeToUse = Math.trunc(state.currentTime)
      logToAndroid(`[AMLL-INFO] Updating lyrics with currentTime=${currentTimeToUse}ms`)


      callPlayer('setLyricLines', state.lyricLines, currentTimeToUse)
      callPlayer('setCurrentTime', currentTimeToUse, true)
      callPlayer('update', 0)

      // if we're currently paused the animation loop will ignore
      // further updates; force a rebuild of the active line so that
      // the UI isn't left completely empty until playback resumes.
      // Delay the reload slightly to give the player a tick to build
      // its internal line objects (fixes case where lyrics arrive
      // while paused but DOM nodes aren't ready yet).
      if (state.isPaused) {
        setTimeout(() => {
          try {
            reloadCurrentLine()
          } catch (_e) {}
        }, 30)
      }
      logToAndroid(`[AMLL-SUCCESS] Updated player with ${state.lyricLines.length} lines`)
    }
    if (backgroundRender) {
      applyBackgroundProfile({ hasLyric: state.lyricLines.length > 0 })
    }

    logToAndroid(`[AMLL-SUCCESS] Updated lyrics (${state.lyricLines.length} lines)`)
    // remember payload so a reload while paused will still display
    try {
      if (Array.isArray(lyricsPayload.lines) && lyricsPayload.lines.length > 0) {
        localStorage.setItem('amll-last-lyrics', JSON.stringify(lyricsPayload))
      } else {
        localStorage.removeItem('amll-last-lyrics')
      }
    } catch (_e) {
      /* ignore quota errors */
    }
    // also save current playback state (time & pause) so we can recreate
    // it on restart
    try {
      localStorage.setItem('amll-last-state', JSON.stringify({
        currentTime: state.currentTime,
        isPaused: state.isPaused,
      }))
    } catch (_e) {
      /* ignore */
    }
  } catch (error) {
    logToAndroid(`[AMLL-ERROR] updateLyrics error: ${error?.message || error}`)
  }
}

window.updateAlbumArt = async function (albumUri) {
  try {
    const uri = String(albumUri ?? '').trim()
    if (!backgroundRender || uri.length === 0 || uri === amllGet('lastAlbumArt')) return

    await backgroundRender.setAlbum(uri)
    lastAlbumArt = uri
    amllSet('lastAlbumArt', uri)
    logToAndroid('[AMLL-SUCCESS] Background album art updated')
  } catch (error) {
    logToAndroid(`[AMLL-ERROR] updateAlbumArt error: ${error?.message || error}`)
  }
}

// helper used when we need to force the player to rebuild/render *only* the
// currently active lyric line. previously we reloaded the whole set of
// lines, which caused a full DOM rebuild of the player and a visible
// flash whenever the play state flipped; the original comment (above) still
// applies, but most of the time only the active line actually needs to be
// reset. rebuilding a single entry also keeps scrolling/placement intact.
//
// The strategy below finds the index of the line that covers the current
// timestamp and invokes the line object's own `rebuildElement()` method. If
// for some reason the library doesn't expose that method we fall back to the
// old behaviour as a safety net.
function reloadCurrentLine() {
  if (!player || !Array.isArray(state.lyricLines) || state.lyricLines.length === 0) return
  const t = Math.trunc(state.currentTime)
  const lines = state.lyricLines
  // find the first line whose interval contains the current time
  let idx = lines.findIndex(l => l.startTime <= t && t < l.endTime)

  // If time falls in a gap between lines (common when paused), keep the
  // most recently visible line instead of wiping the DOM clean.
  if (idx === -1) {
    for (let i = lines.length - 1; i >= 0; i -= 1) {
      if (lines[i].startTime <= t) {
        idx = i
        break
      }
    }
  }

  // If no line is applicable (e.g. before the first lyric), do nothing.
  if (idx === -1) {
    return
  }

  const obj = player.currentLyricLineObjects?.[idx]
  if (obj && typeof obj.rebuildElement === 'function') {
    // clear built flag so rebuildElement actually recreates the DOM
    obj.built = false

    // dump some pre-rebuild diagnostics
    try {
      const totalLines = player?.currentLyricLineObjects?.length ?? 0
      const playerEl = player?.getElement && player.getElement()
      const playerChildren = playerEl ? playerEl.childElementCount : 0
      logToAndroid(`[AMLL-DUMP] pre-rebuild idx=${idx} totalLines=${totalLines} playerChildren=${playerChildren}`)
      const maybeLine = obj.getLine && obj.getLine()
      try {
        logToAndroid(`[AMLL-DUMP] pre-rebuild lineData idx=${idx} => ${JSON.stringify(maybeLine)}`)
      } catch (_e) {
        logToAndroid(`[AMLL-DUMP] pre-rebuild lineData idx=${idx} => [unserializable]`)
      }
    } catch (_e) {}

    // perform rebuild then poll for a non-empty element before giving up
    obj.rebuildElement()
    // recalc layout so any size changes are applied immediately
    player.calcLayout && player.calcLayout(true)

    // helper: poll for meaningful content on the rebuilt element
    async function pollForElementContent(targetObj, maxWaitMs = 200, intervalMs = 10) {
      const attempts = Math.max(1, Math.ceil(maxWaitMs / intervalMs))
      for (let i = 0; i < attempts; i += 1) {
        try {
          const el = targetObj.getElement && targetObj.getElement()
          const textLen = el ? (el.textContent || '').trim().length : 0
          const childCount = el ? el.childElementCount : 0
          if (textLen > 0 || childCount > 0) return true
        } catch (_e) {}
        // wait before next try
        // eslint-disable-next-line no-await-in-loop
        await new Promise((r) => setTimeout(r, intervalMs))
      }
      return false
    }

    // detailed post-rebuild diagnostics: element child count and text length
    try {
      const el = obj.getElement && obj.getElement()
      const textLen = el ? (el.textContent || '').trim().length : 0
      const childCount = el ? el.childElementCount : 0
      const outerLen = el ? (el.outerHTML || '').length : 0
      logToAndroid(`[AMLL-DUMP] post-rebuild idx=${idx} childCount=${childCount} textLen=${textLen} outerLen=${outerLen}`)

      // also try to re-log the line data after rebuild
      try {
        const lineData = obj.getLine && obj.getLine()
        logToAndroid(`[AMLL-DUMP] post-rebuild lineData idx=${idx} => ${JSON.stringify(lineData)}`)
      } catch (_e) {
        logToAndroid(`[AMLL-DUMP] post-rebuild lineData idx=${idx} => [unserializable]`)
      }

      // if element still empty, attempt short polling before immediate fallback
      const hasContent = (textLen > 0) || (childCount > 0)
      if (!hasContent) {
        logToAndroid(`[AMLL-WARN] rebuilt line ${idx} produced empty element — starting poll (max 200ms) at ${t}ms`)
        try {
          pollForElementContent(obj, 200, 10).then((ok) => {
            if (ok) {
              logToAndroid(`[AMLL-DEBUG] reloadCurrentLine poll succeeded for idx=${idx} at ${t}ms`)
            } else {
              logToAndroid(`[AMLL-WARN] reloadCurrentLine poll failed for idx=${idx}, performing immediate full reload at ${t}ms`)
              try {
                callPlayer('setLyricLines', lines, t)
                callPlayer('setCurrentTime', t, true)
                callPlayer('update', 0)
                logToAndroid(`[AMLL-DEBUG] reloadCurrentLine immediate full reload executed at ${t}ms`)
              } catch (err) {
                logToAndroid(`[AMLL-ERROR] reloadCurrentLine immediate fallback failed: ${err?.message || err}`)
              }
            }
          }).catch((err) => {
            logToAndroid(`[AMLL-ERROR] reloadCurrentLine poll error: ${err?.message || err}`)
          })
        } catch (err) {
          logToAndroid(`[AMLL-ERROR] reloadCurrentLine poll setup failed: ${err?.message || err}`)
        }
      } else {
        logToAndroid(`[AMLL-DEBUG] reloadCurrentLine rebuilt index ${idx} at ${t}ms`)
      }
    } catch (err) {
      logToAndroid(`[AMLL-ERROR] reloadCurrentLine verify error: ${err?.message || err}`)
    }
  } else {
    // safe fallback if the internal structure is not what we expect
    callPlayer('setLyricLines', lines, t)
    callPlayer('setCurrentTime', t, true)
    callPlayer('update', 0)
    logToAndroid(`[AMLL-DEBUG] reloadCurrentLine (fallback full reload) at ${t}ms`)
  }
  // always issue an explicit update so paused state still paints the
  // current line; without this the rebuilt DOM can sit invisible until
  // the next resume trigger.
  callPlayer('update', 0)
}


// when pausing we only want to lock the mask-size property so the
// highlight width remains fixed. other animations (float, color, etc.)
// should continue running so the still‑paused view still looks "alive".
//
// this helper freezes *just* maskSize because that was the only property
// causing the green box issue; maskPosition will continue updating so the
// highlight can still scroll horizontally if the animation loop is running.
function freezeMaskSizeOnly() {
  if (!player || !player.currentLyricLineObjects) return
  for (const lineObj of player.currentLyricLineObjects) {
    if (!Array.isArray(lineObj.splittedWords)) continue
    for (const w of lineObj.splittedWords) {
      const el = w.mainElement
      if (el) {
        const cs = window.getComputedStyle(el)
        const size = cs.maskSize || cs.webkitMaskSize
        if (size) {
          el.style.maskSize = size
          el.style.webkitMaskSize = size
        }
      }
      // leave any maskAnimations running
    }
  }
}

window.setPaused = function (paused) {
  // explicit API: freeze or resume the current active line animations
  const st = amllGet('state') || state
  if (paused) {
    if (!st.isPaused) {
      st.isPaused = true
      // don't call player.pause(): the library's internal pause mode
      // clears DOM and halts background work, which makes lines vanish
      // when we pause mid‑buffer. our own animationFrameLoop already
      // ignores updates when `isPaused` is true, so we can just leave the
      // player running and manually freeze visuals.
      const el = player?.getElement?.()
      if (el) el.classList.add('amll-paused')
      reloadCurrentLine()
      freezeMaskSizeOnly()
    }
  } else {
    if (st.isPaused) {
      st.isPaused = false
      // likewise avoid player.resume(); the animation loop will restart
      // updates automatically when `isPaused` flips to false.
      const el = player?.getElement?.()
      if (el) el.classList.remove('amll-paused')
      reloadCurrentLine()
      document.documentElement.style.removeProperty('--amll-player-time')
    }
  }
  // persist state each time it changes
  try {
    localStorage.setItem('amll-last-state', JSON.stringify({
      currentTime: st.currentTime,
      isPaused: st.isPaused,
    }))
  } catch (_e) {}
}

window.updateTime = function (timeMs, isPausedArg) {
  const now = performance.now()
  const parsedTime = Number(timeMs)
  const st = amllGet('state') || state
  const prev = st.currentTime
  st.currentTime = Number.isFinite(parsedTime) ? parsedTime : 0

  // if caller explicitly passed a paused flag, trust it instead of
  // inferring from the time value. this lets the host bridge tell us
  // when playback stopped (useful for e.g. seek-as-pause events).
  const explicitPause = (typeof isPausedArg === 'boolean') ? isPausedArg : null

  if (explicitPause !== null) {
    if (explicitPause && !st.isPaused) {
      st.isPaused = true
      logToAndroid('[AMLL-PLAY] pause flag received, player paused')
      const el = player?.getElement?.()
      if (el) el.classList.add('amll-paused')
      reloadCurrentLine()
      freezeMaskSizeOnly()
    } else if (!explicitPause && st.isPaused) {
      st.isPaused = false
      logToAndroid('[AMLL-PLAY] resume flag received, player resumed')
      const el = player?.getElement?.()
      if (el) el.classList.remove('amll-paused')
      reloadCurrentLine()
      document.documentElement.style.removeProperty('--amll-player-time')
    }
    // still handle backward seeks when time moves backwards
    if (st.currentTime < prev) {
      logToAndroid(`[AMLL-PLAY] backward seek (${prev} -> ${st.currentTime}), reloading line`)
      reloadCurrentLine()
    }
  } else {
    // fall back to original detection logic if no flag supplied
    if (st.currentTime === prev) {
      if (!st.isPaused) {
        st.isPaused = true
        // don't invoke player.pause so DOM stays populated
        logToAndroid('[AMLL-PLAY] detected pause, player paused')
        const el = player?.getElement?.()
        if (el) el.classList.add('amll-paused')
        reloadCurrentLine()
        freezeMaskSizeOnly()
      }
    } else {
      if (st.isPaused) {
        st.isPaused = false
        // again avoid player.resume; our own loop will handle updates
        logToAndroid('[AMLL-PLAY] playback resumed, player resumed')
        const el = player?.getElement?.()
        if (el) el.classList.remove('amll-paused')
        // ensure any active line restarts its animation
        reloadCurrentLine()
      }
      // backward jump also counts as a replay/seek
      if (st.currentTime < prev) {
        logToAndroid(`[AMLL-PLAY] backward seek (${prev} -> ${st.currentTime}), reloading line`)
        reloadCurrentLine()
      }
    }
  }

  updateSeekingStateFromTime(now, st.currentTime)
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

window.setBlurEnabled = function (enabled) {
  const shouldEnable = Boolean(enabled)
  if (player && state.blur.enabled !== shouldEnable) {
    callPlayer('setEnableBlur', shouldEnable)
    state.blur.enabled = shouldEnable
    logToAndroid(`[AMLL-BLUR] setBlurEnabled(${shouldEnable})`)
    
    // 仅在启用模糊时清除计时器，禁用时不清除
    if (shouldEnable && state.blur.timeoutId !== null) {
      clearTimeout(state.blur.timeoutId)
      state.blur.timeoutId = null
    }
  }
}

window.setBlurTimeout = function (timeMs) {
  const ms = Number(timeMs)
  if (Number.isFinite(ms) && ms > 0) {
    state.blur.TIMEOUT_MS = ms
    logToAndroid(`[AMLL-BLUR] Blur timeout set to ${ms}ms`)
  }
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

  const base = amllGet('currentBackgroundProfile') || currentBackgroundProfile
  const next = {
    ...base,
    ...options,
  }
  if (typeof next.renderer === 'string') {
    next.renderer = next.renderer.toLowerCase() === 'mesh' ? 'mesh' : 'pixi'
  } else {
    next.renderer = base.renderer
  }

  const rendererChanged = next.renderer !== base.renderer
  currentBackgroundProfile = next
  amllSet('currentBackgroundProfile', next)

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

// capture uncaught errors in JS and forward to Android logcat
window.onerror = function (msg, src, line, col, err) {
  logToAndroid(`[AMLL-ERROR] Uncaught JS: ${msg} at ${src}:${line}:${col} ${err?err.stack:''}`)
}

window.setFontSettings = setFontSettings

window.addEventListener('DOMContentLoaded', () => {
  document.documentElement.style.background = 'transparent'
  document.body.style.background = 'transparent'
  // start visibility logging early so we capture host-driven pause/hidden events
  initVisibilityLogging()
  
  // 检查 Android 接口是否可用
  if (typeof Android !== 'undefined' && Android?.log) {
    if (typeof Android.onLineClick === 'function') {
      logToAndroid('[AMLL-INIT] Android.onLineClick interface is ready')
    } else {
      logToAndroid('[AMLL-INIT] WARNING: Android.onLineClick interface NOT found')
    }
  } else {
    logToAndroid('[AMLL-INIT] WARNING: Android interface NOT available')
  }
  
  mountPlayer()

  // if we previously stored lyrics in localStorage (e.g. user refreshed
  // while paused) reapply them now. this avoids a blank screen until the
  // host bridge re-sends the payload.
  try {
    const saved = localStorage.getItem('amll-last-lyrics')
    if (saved) {
      const payload = JSON.parse(saved)
      if (payload && Array.isArray(payload.lines) && payload.lines.length > 0) {
        logToAndroid('[AMLL-INIT] restoring lyrics from localStorage')
        updateLyrics(payload)
      }
    }
    // restore playback state too
    const stateSaved = localStorage.getItem('amll-last-state')
    if (stateSaved) {
      const st = JSON.parse(stateSaved)
      if (st && typeof st.isPaused === 'boolean') {
        state.currentTime = Number.isFinite(st.currentTime) ? st.currentTime : state.currentTime
        state.isPaused = st.isPaused
        if (player) {
          // make sure the player itself reflects the restored time so
          // that the correct line is already visible (or will be when we
          // freeze it below). without this the UI could show the first
          // lyric while the timestamp sits later in the song, which can
          // look like a blank screen if the early range has no content.
          callPlayer('setCurrentTime', state.currentTime, true)
        }
        if (state.isPaused) {
          // call setPaused to apply visual freezing; it will also
          // rebuild the current line so that the paused view isn’t
          // empty when the page is loaded while playback is stopped.
          setPaused(true)
        }
        logToAndroid(`[AMLL-INIT] restored state paused=${state.isPaused} time=${state.currentTime}`)
      }
    }
  } catch (_e) {
    // ignore malformed JSON
  }

  // development-only: if no Android bridge, inject a sample lyric to verify layout
  if (typeof Android === 'undefined') {
    logToAndroid('[AMLL-DEV] no Android object, inserting demo lyric')
    updateLyrics({
      lines: [{
        words: [
          { word: 'Hello', startTime: 0, endTime: 2000 },
          { word: 'world', startTime: 2000, endTime: 4000 }
        ],
        startTime: 0,
        endTime: 4000,
        translatedLyric: '',
        romanLyric: '',
        isBG: false,
        isDuet: false
      }]
    })
  }

  const styleTag = document.createElement('style')
  styleTag.id = 'amll-touch-bg-blur-style'
  styleTag.textContent = `
    .amll-lyric-player.${TOUCH_BG_BLUR_CLASS} [class*="lyricBgLine"] {
      filter: blur(var(--amll-touch-bg-blur, 10px)) !important;
    }

    .amll-lyric-player.${TOUCH_BG_BLUR_CLASS} [class*="lyricBgLine"][class*="active"] {
      filter: none !important;
    }
  `
  document.head.appendChild(styleTag)
})

window.addEventListener('beforeunload', () => {
  // 清除blur计时器
  if (state.blur.timeoutId !== null) {
    clearTimeout(state.blur.timeoutId)
    state.blur.timeoutId = null
  }
  
  if (rafId != null) {
    window.cancelAnimationFrame(rafId)
    rafId = null
  }
  if (backgroundRender) {
    backgroundRender.dispose()
    backgroundRender = null
  }

  const styleTag = document.getElementById('amll-touch-bg-blur-style')
  styleTag?.remove()
})
