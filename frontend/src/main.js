import { LyricPlayer, BackgroundRender, PixiRenderer, MeshGradientRenderer } from '@applemusic-like-lyrics/core'
import '@applemusic-like-lyrics/core/style.css'

const PLAYER_BACKGROUND = 'transparent'
const SEEK_THRESHOLD_MS = 900
const SEEK_HOLD_MS = 180
const DEFAULT_FONT_STACK = '"SF Pro Display", "PingFang SC", system-ui, -apple-system, "Segoe UI", sans-serif'
const DYNAMIC_FONT_STYLE_ID = 'amll-dynamic-font-face-style'

const QUALITY_PROFILE = {
  alignAnchor: 'center',
  // active line will land at 25% of the viewport from top of the *visible area*
  // we introduce a half-screen padding above the first line, so the
  // effective offset needs to be reduced by that amount
  alignPosition: -0.25,
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

// helper for inserting a small stylesheet so that an individual lyric line
// can be marked with `amll-line-unblur` and the rule will remove any
// blur filter the core library may have applied.
const UNBLUR_STYLE_ID = 'amll-unblur-style'
const PAUSE_STYLE_ID = 'amll-pause-style'

function ensureUnblurStyle() {
  if (document.getElementById(UNBLUR_STYLE_ID)) return
  const s = document.createElement('style')
  s.id = UNBLUR_STYLE_ID
  s.textContent = `[class*="_lyricLine_"] .amll-line-unblur, [class*="_lyricLine_"].amll-line-unblur { filter: none !important; }`
  document.head.appendChild(s)
}

// pause styling helper (see comment in main.jsx)
function ensurePauseStyle() {
  if (document.getElementById(PAUSE_STYLE_ID)) return
  const s = document.createElement('style')
  s.id = PAUSE_STYLE_ID
  s.textContent = `
/* freeze only the main-line text when paused (ignore duet/background/etc) */
.amll-lyric-player.amll-paused [class*="_lyricMainLine_"][class*="_active_"] *,
.amll-lyric-player.amll-paused [class*="_lyricMainLine_"][class*="_active_"] ._romanWord_*,
.amll-lyric-player.amll-paused [class*="_lyricMainLine_"][class*="_active_"] ._emphasizeWrapper_*,
/* also prevent any span under the active main line from transforming */
.amll-lyric-player.amll-paused [class*="_lyricMainLine_"][class*="_active_"] span {
  animation-play-state: paused !important;
  transition-duration: 0s !important;
  transform: none !important;
}
`
  document.head.appendChild(s)
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
      endTime: Math.max(Number.isFinite(startTime) ? startTime : 0, Number.isFinite(endTime) ? endTime : 0),
      isBG: Boolean(line?.isBG),
      isDuet: Boolean(line?.isDuet),
    }

    // 调试背景歌词的翻译
    if (result.isBG) {
      const wordsText = words.map(w => w.word).join('')
      logToAndroid(`[BG-LYRICS-DEBUG] Background line: "${wordsText}" | translation: "${result.translatedLyric}" | roman: "${result.romanLyric}"`)
    }

    return result
  })
}

const state = {
  lyricLines: [],
  currentTime: 0,
  isPaused: false, // whether the host reports playback paused
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
  get state() { return state }, set state(v) { state = v }
})

function amllGet(name){return window.__amll[name]}

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
  // keep the animated flow fixed to the viewport so it never scrolls
  bgElement.style.position = 'fixed'
  bgElement.style.top = '0'
  bgElement.style.left = '0'
  bgElement.style.width = '100vw'
  bgElement.style.height = '100vh'
  bgElement.style.zIndex = '0'
  // prepend to body so it sits behind everything
  document.body.prepend(bgElement)

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

  // unblur the line under the finger
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
        }
      }
    } catch (error) {
      logToAndroid(`[AMLL-TAP-ERROR] ${error?.message || error}`)
    }
  }

  // cleanup unblur classes
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
  element.style.height = 'auto' // let height grow so document scrolling works
  element.style.background = PLAYER_BACKGROUND
  // remove blending; plus-lighter can make white text disappear on bright or
  // similarly colored backgrounds. plain rendering ensures visibility.
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

function animationFrameLoop() {
  if (!player) return

  try {
    const now = performance.now()
    const delta = lastFrameTime === -1 ? 0 : now - lastFrameTime
    lastFrameTime = now

    if (state.isPaused) {
      // keep the player rendering the current line even while paused.
      // this ensures lyrics remain visible / updated when the host pauses
      // playback and may still send new lyrics data.
      // Note: passing `isSeek=true` causes the core player to disable/enable
      // lyric line objects, which can pause their internal animations.
      callPlayer('setCurrentTime', Math.trunc(state.currentTime), false)
      callPlayer('update', 0)
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

  // ensure the helper stylesheets we rely on are present
  ensureUnblurStyle()
  ensurePauseStyle()

  // make the outer document scrollable
  document.documentElement.style.overflowY = 'auto'
  document.body.style.overflowY = 'auto'

  app.innerHTML = ''
  app.style.background = PLAYER_BACKGROUND

  // 重置专辑图缓存，确保新的 backgroundRender 实例会重新加载专辑图
  lastAlbumArt = ''

  try {
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

    // default behavior: put lyric player in document flow and allow
    // natural scrolling. this keeps each line arranged by the core library.
    player.allowScroll = true

    // disable internal scroll handling so page scroll is not blocked
    player.beginScrollHandler = () => false

    // keep original layout logic, but strip out its scrolling helpers and
    // reset any transforms it applies.
    const originalCalcLayout2 = player.calcLayout.bind(player)
    player.calcLayout = async function(animated = false) {
      await originalCalcLayout2(animated)
      const sp = document.getElementById('amll-spacer')
      if (sp && sp.parentElement) sp.parentElement.removeChild(sp)
      if (this.currentLyricLineObjects) {
        this.currentLyricLineObjects.forEach(o => {
          const el = o.getElement()
          if (el) el.style.transform = ''
        })
      }
      if (this.element) this.element.style.height = 'auto'
    }

    const playerElement = player.getElement()
    applyPlayerStyle(playerElement)
    if (player.allowScroll) {
      playerElement.style.position = 'relative'
    }
    playerElement.style.zIndex = '1'
    app.appendChild(playerElement)

    // allow the browser to handle vertical panning natively; the fixed
    // overlay no longer needs to intercept or proxy gestures. touch-action
    // tells the engine that vertical scrolls are permitted.
    playerElement.style.touchAction = 'pan-y'
    let _lastY = null
    playerElement.addEventListener('touchstart', (e) => {
      if (e.touches.length === 1) {
        _lastY = e.touches[0].clientY
      }
    }, { passive: true })
    playerElement.addEventListener('touchend', () => { _lastY = null })

    // wheel events bubble and scroll the page normally without special handling.

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

  // always use fresh quality profile, not relying on possibly undefined currentProfile
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

    // debug dump first few lines to help diagnose visibility issues
    if (state.lyricLines.length > 0) {
      state.lyricLines.slice(0, 5).forEach((ln, idx) => {
        logToAndroid(`[AMLL-DUMP] normalized line ${idx}: text="${ln.words.map(w=>w.word).join('')}" isBG=${ln.isBG} isDuet=${ln.isDuet}`)
      })
    } else {
      logToAndroid('[AMLL-DUMP] no lyric lines after normalization')
    }

    if (player) {
      const currentTimeToUse = Math.trunc(state.currentTime)
      logToAndroid(`[AMLL-INFO] Updating lyrics with currentTime=${currentTimeToUse}ms`)
      callPlayer('setLyricLines', state.lyricLines, currentTimeToUse)
      callPlayer('setCurrentTime', currentTimeToUse, true)
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

// see comment in main.jsx for rationale
function reloadCurrentLine() {
  if (!player || !Array.isArray(state.lyricLines) || state.lyricLines.length === 0) return
  const t = Math.trunc(state.currentTime)
  callPlayer('setLyricLines', state.lyricLines, t)
  callPlayer('setCurrentTime', t, true)
  callPlayer('update', 0)
  logToAndroid(`[AMLL-DEBUG] reloadCurrentLine at ${t}ms`)
}

window.setPaused = function (paused) {
  if (paused) {
    if (!state.isPaused) {
      state.isPaused = true
      // Keep the core player running; we freeze visuals via CSS instead.
      const el = player?.getElement?.()
      if (el) el.classList.add('amll-paused')
      document.documentElement.style.setProperty('--amll-player-time', `${state.currentTime}`)
    }
  } else {
    if (state.isPaused) {
      state.isPaused = false
      const el = player?.getElement?.()
      if (el) el.classList.remove('amll-paused')
      reloadCurrentLine()
      document.documentElement.style.removeProperty('--amll-player-time')
    }
  }
}

window.updateTime = function (timeMs) {
  const now = performance.now()
  const parsedTime = Number(timeMs)
  const prev = state.currentTime
  state.currentTime = Number.isFinite(parsedTime) ? parsedTime : 0

  // pause/resume detection
  if (state.currentTime === prev) {
    if (!state.isPaused) {
      state.isPaused = true
      logToAndroid('[AMLL-PLAY] detected pause, player paused (render loop continues)')
      const el = player?.getElement?.()
      if (el) el.classList.add('amll-paused')
      document.documentElement.style.setProperty('--amll-player-time', `${state.currentTime}`)
    }
  } else {
    if (state.isPaused) {
      state.isPaused = false
      logToAndroid('[AMLL-PLAY] playback resumed, player resumed')
      const el = player?.getElement?.()
      if (el) el.classList.remove('amll-paused')
      reloadCurrentLine()
      document.documentElement.style.removeProperty('--amll-player-time')
    }
    if (state.currentTime < prev) {
      logToAndroid(`[AMLL-PLAY] backward seek (${prev} -> ${state.currentTime}), reloading line`)
      reloadCurrentLine()
    }
  }

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
  if (window.__amll) window.__amll.currentBackgroundProfile = next

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

window.setFontSettings = setFontSettings

window.addEventListener('DOMContentLoaded', () => {
  document.documentElement.style.background = 'transparent'
  document.body.style.background = 'transparent'
  
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
