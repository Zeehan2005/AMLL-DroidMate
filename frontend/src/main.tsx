/**
 * AMLL 前端主入口 (Main Entry Point)
 *
 * 这个文件是嵌入到 Android WebView 中的 Web 歌词界面的核心逻辑。
 * 它充当了 @applemusic-like-lyrics/core (核心渲染库) 与 Android 原生代码之间的桥梁。
 *
 * 主要职责：
 * 1. 初始化渲染引擎 (LyricPlayer) 和背景效果 (BackgroundRender)。
 * 2. 暴露全局 API (挂载到 window)，供 Android 端通过 `evaluateJavascript` 调用。
 * 3. 管理歌词状态、播放进度、专辑封面以及 UI 配置。
 * 4. 处理布局适配和针对 WebView 环境的性能优化。
 */

import * as AMLLCore from '@applemusic-like-lyrics/core'
import '@applemusic-like-lyrics/core/style.css'
import '../styles.css'
import { logToAndroid } from './utils/bridge_utils'

// --- 全局类型声明 (Global Type Declarations) ---
// 声明挂载在 window 对象上的 API，以便 Android 端和 TypeScript 类型检查使用
declare global {
  interface Window {
    __amll?: any // 暴露给调试用的内部实例
    updateLyrics?: (payload: any) => void // 更新歌词数据
    updateTime?: (timeMs: number) => void // 兼容旧版：立即跳转到指定时间
    syncPlaybackTime?: (payload: { p: number; s: boolean } | number) => void // 原生低频时间校准
    seekPlaybackTime?: (timeMs: number) => void // 跳转（强制重置本地时钟）
    updateAlbumArt?: (uri: string) => Promise<void> // 更新专辑封面
    setPaused?: (paused: boolean) => void // 设置播放/暂停状态
    configureLyricMotion?: (options: any) => void // 配置歌词滚动/动画效果
    configureBackgroundEffect?: (options: any) => void // 配置背景渲染参数
    configureLyricBackground?: (options: any) => void // 配置底色或渲染器切换
    setRenderMode?: (mode: string) => void // 设置渲染模式（如流式、静态）
    setLyricPlayerImplementation?: (implementation: string) => void // 切换播放器实现
    setLyricSizePreset?: (preset: string) => void // 设置歌词字体大小预设
    setEnableTranslationLine?: (enabled: boolean) => void // 启用/禁用翻译行
    setEnableRomanLine?: (enabled: boolean) => void // 启用/禁用罗马音行
    setEnableSwapTransRomanLine?: (enabled: boolean) => void // 交换翻译和罗马音的位置
    setAdvanceLyricDynamicLyricTime?: (enabled: boolean) => void // 启用歌词提前量优化
    applyFontSettings?: (config: {
      effectiveFamily?: string
      files?: Array<{ familyName: string, uri: string }>
    }) => void // 应用字体设置：注入 @font-face 规则并设置 CSS 变量
    rebuildLyricsDom?: (reason?: string) => boolean // 强制重构歌词 DOM（处理布局异常）

    // Android 原生通过 JavascriptInterface 注入的对象
    Android?: {
      log?: (message: string, level: string) => void // 向原生发送日志
      isPlaying?: () => boolean // 查询原生播放状态
      onLineClick?: (index: number, startTime: number) => void // 歌词行点击回调
      onPageReady?: () => void // 页面就绪回调
      onLyricsParsedResult?: (json: string) => void // 解析结果回调 (Parser 专用)
    }
    AMLLCore: typeof AMLLCore
    parseLyrics?: (raw: string, format: string) => Promise<void> // 解析歌词入口
  }
}

// --- 内部状态管理 (Internal State Management) ---
const state = {
  player: null as any, // LyricPlayer 实例
  background: null as any, // BackgroundRender 实例
  currentTime: -1, // 当前毫秒级播放时间
  lyricLines: [] as any[], // 当前加载的歌词行数据
  // 默认占位图 (SVG Base64)
  albumUri: '',
  isPlaying: false, // 播放状态缓存
  hasPlaybackState: false, // 是否已接收过播放状态
  pendingLyricOptions: {} as Record<string, any>, // 待应用的配置项
}

/**
 * 播放时钟：与网页版一致，时间由 WebView 内部的 rAF 逐帧推进。
 * Android 只提供低频锚点（位置 + 播放状态），不再每帧跨语言调用。
 */
const playbackClock = {
  positionMs: 0,             // 最近一次锚点对应的播放位置
  anchorPerfMs: 0,           // 锚点对应的 performance.now()
  wallMs: 0,                 // 原生采样时间戳（仅用于诊断）
  playing: false,
  hasAnchor: false,
  correctionMs: 0,           // 待平滑消除的校准误差
  correctionStartPerfMs: 0,
  lastNativePositionMs: Number.NaN, // 上一次原生上报的位置（用于忽略重复采样）
}
const CLOCK_CORRECTION_DURATION_MS = 300
const CLOCK_SEEK_THRESHOLD_MS = 1500
const CLOCK_MIN_CORRECTION_MS = 40

/** 读取当前帧的播放时间；误差较大时立即重置，小幅误差在 300ms 内平滑消化 */
function getPlaybackTime(nowPerfMs: number): number {
  if (!playbackClock.hasAnchor) return state.currentTime > 0 ? state.currentTime : 0
  let time = playbackClock.positionMs + (playbackClock.playing ? nowPerfMs - playbackClock.anchorPerfMs : 0)
  const elapsed = nowPerfMs - playbackClock.correctionStartPerfMs
  if (playbackClock.correctionMs !== 0 && elapsed >= 0 && elapsed < CLOCK_CORRECTION_DURATION_MS) {
    time += playbackClock.correctionMs * (1 - elapsed / CLOCK_CORRECTION_DURATION_MS)
  }
  return Math.max(0, time)
}

/** 用新的锚点硬重置本地时钟（首帧、播放状态切换或跳转） */
function resetPlaybackClock(positionMs: number, playing: boolean, nowPerfMs = performance.now()) {
  playbackClock.positionMs = positionMs
  playbackClock.anchorPerfMs = nowPerfMs
  playbackClock.playing = playing
  playbackClock.hasAnchor = true
  playbackClock.correctionMs = 0
  playbackClock.lastNativePositionMs = positionMs
  state.currentTime = Math.round(positionMs)
}

/** 播放/暂停状态：只改一次播放器状态，供 setPaused 与 syncPlaybackTime 共用 */
function applyPlaybackState(playing: boolean) {
  if (state.hasPlaybackState && state.isPlaying === playing) return
  state.isPlaying = playing
  state.hasPlaybackState = true
  playbackClock.playing = playing
  const p = state.player
  if (!p) return
  try {
    if (playing) p.resume?.()
    else p.pause?.()
  } catch (e) {
    log(`setPaused error: ${(e as Error).message}`, 'error')
  }
}

// 将 Core 挂载到 window 确保全局可用（方便 HMR 或调试）
;(window as any).AMLLCore = AMLLCore

// --- 工具函数 (Utility Functions) ---

/**
 * 统一日志输出，如果 Android 接口可用则发送给原生，否则输出到控制台
 */
const log = (msg: string, level: 'info' | 'debug' | 'warn' | 'error' = 'info') => logToAndroid(msg, level)

/**
 * 设置 CSS 全局变量，用于响应式修改样式
 */
const setCSSVar = (name: string, value: string | number | boolean) => {
  const val = typeof value === 'boolean' ? (value ? '1' : '0') : String(value)
  document.documentElement.style.setProperty(name, val)
}

/**
 * 将 HTML 元素挂载到根节点，并应用基础全屏样式
 */
function attachElementToRoot(root: HTMLElement, el: HTMLElement, zIndex: string) {
  Object.assign(el.style, {
    position: 'absolute',
    inset: '0',
    width: '100%',
    height: '100%',
    pointerEvents: 'none', // 默认不拦截点击事件
    zIndex
  })
  if (el.parentElement !== root) {
    root.appendChild(el)
  }
}

// --- 核心初始化逻辑 (Core Initialization) ---

/**
 * 创建背景渲染器
 * 尝试按优先级初始化不同的渲染引擎 (如 MeshGradient 或 Pixi)
 */
function createBackgroundRenderer(core: any, root: HTMLElement, selectedRenderer?: string) {
  const Background = core.BackgroundRender
  if (!Background?.new) {
    log('BackgroundRender factory not found on core', 'debug')
    return null
  }

  // 定义可用的候选渲染器映射
  const allCandidates: Record<string, any> = {
    'mesh': core.MeshGradientRenderer,
    'pixi': core.PixiRenderer
  }

  // 如果显式选定了存在的渲染器，则只尝试那一个；否则使用默认的优先级列表
  const rendererCandidates = (selectedRenderer && allCandidates[selectedRenderer])
    ? [allCandidates[selectedRenderer]]
    : [core.MeshGradientRenderer, core.PixiRenderer].filter(Boolean)

  for (const RendererCtor of rendererCandidates) {
    try {
      const instance = Background.new(RendererCtor)
      const element = instance.getElement()
      attachElementToRoot(root, element, '-1') // 放在最底层
      log(`Created BackgroundRender with ${RendererCtor?.name || 'renderer'}`, 'debug')
      return instance
    } catch (e) {
      log(`BackgroundRender init failed with ${RendererCtor?.name || 'renderer'}: ${(e as Error).message}`, 'warn')
    }
  }
  return null
}





/**
 * 初始化 AMLL 核心环境
 */
function initAMLL() {
  try {
    // 设置页面背景透明，以便 Android 底层背景可见
    document.documentElement.style.background = 'transparent'
    document.body.style.background = 'transparent'

    const root = document.getElementById('app') || document.createElement('div')
    if (!document.getElementById('app')) {
      root.id = 'app'
      document.body.appendChild(root)
    }
    Object.assign(root.style, { position: 'relative', width: '100%', height: '100vh' })

    const Core = AMLLCore as any
    // 获取播放器类（支持不同版本的命名习惯）
    const DomLyricPlayer = Core.DomLyricPlayer

    if (DomLyricPlayer) {
      try {
        state.player = new DomLyricPlayer({
          container: root,
          album: state.albumUri,
        })
        // 显式挂载播放器元素（如果库没有自动挂载）
        const el = state.player.getElement?.() || state.player.element
        if (el) {
          Object.assign(el.style, { position: 'absolute', inset: '0', zIndex: '1', pointerEvents: 'auto' })
          if (el.parentElement !== root) {
            root.appendChild(el)
          }
        }
        // Forward lyric line clicks to Android and optimistically seek locally.
        const onLineClick = (evt: any) => {
          const detail = evt?.detail || {}
          const lineIndex = typeof evt?.lineIndex === 'number' ? evt.lineIndex : (typeof detail.lineIndex === 'number' ? detail.lineIndex : -1)
          const fallbackLine = (lineIndex >= 0 && state.lyricLines?.[lineIndex]) ? state.lyricLines[lineIndex] : undefined

          const getStart = (obj: any) => (typeof obj?.startTime === 'number' ? obj.startTime : (typeof obj?.start === 'number' ? obj.start : undefined))
          const startTime = getStart(evt) ?? getStart(detail) ?? getStart(evt?.line) ?? getStart(detail.line) ?? getStart(fallbackLine)

          log(`line-click: index=${lineIndex}, start=${startTime}`, 'debug')
          if (startTime === undefined) return
          window.Android?.onLineClick?.(lineIndex, startTime)
          // 乐观跳转：立即重置本地时钟，下一帧由 rAF 统一渲染
          window.seekPlaybackTime?.(startTime)
        }
        state.player.addEventListener?.('line-click', onLineClick)
        el?.addEventListener?.('line-click', onLineClick)
        log('Created and attached DomLyricPlayer', 'debug')
      } catch (e) {
        log(`Failed to instantiate DomLyricPlayer: ${(e as Error).message}`, 'error')
      }
    }

    // 初始化背景渲染器（默认使用 mesh 渲染器，后续可通过 configureLyricBackground 切换）
    if (!state.background) {
      state.background = createBackgroundRenderer(Core, root as HTMLElement)
    }

    // 暴露内部实例以便调试
    window.__amll = { player: state.player, backgroundRender: state.background, clock: playbackClock }

    // 启动持续更新循环：与网页版一致，逐帧从本地时钟推进歌词时间
    // 原生不再参与逐帧同步，只在低频锚点到来时校准（见 window.syncPlaybackTime）
    let lastTime = performance.now()
    const tick = (now: number) => {
      const delta = now - lastTime
      lastTime = now
      if (state.player) {
        const t = Math.round(getPlaybackTime(now))
        // 核心库要求 setCurrentTime 尽量频繁；同一毫秒内则不重复触发布局计算
        if (t !== state.currentTime) {
          state.currentTime = t
          const p = state.player
          if (p.setCurrentTime) p.setCurrentTime(t, false)
          else if (p.seek) p.seek(t)
        }
        // 每帧只推进一次动画：不再在 updateTime 里额外调用 update(0)
        state.player.update?.(delta)
      }
      requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)

    // 如果初始化时已有歌词，同步给播放器并执行一次完整的布局计算
    if (state.lyricLines.length > 0 && state.player) {
      const p = state.player
      const setter = p.setLyricLines || p.setLyrics || p.updateLyrics
      if (setter) {
        setter.call(p, state.lyricLines)
        p.calcLayout?.()
        p.update?.(0)
        log(`Applied ${state.lyricLines.length} pending lines to new player`, 'info')
      }
    }

    log('AMLL core WebView initialized', 'debug')
    // 通知 Android 端页面已就绪
    window.Android?.onPageReady?.()
  } catch (error) {
    log(`Initialization error: ${(error as Error).message}`, 'error')
  }
}

// --- 启动初始化进程 ---
if (document.readyState === 'loading') {
  window.addEventListener('DOMContentLoaded', initAMLL)
} else {
  setTimeout(initAMLL, 0)
}

// --- 全局 API 实现 (供 Android 端调用) ---

/**
 * 更新歌词数据
 * @param payload 包含原始歌词文本或结构化歌词的对象
 */
window.updateLyrics = (payload: any) => {
  try {
    const lines = Array.isArray(payload?.lines) ? payload.lines : []
    state.lyricLines = lines
    log(`updateLyrics: ${lines.length} lines`, 'debug')

    const p = state.player
    if (p) {
      const setter = p.setLyricLines || p.setLyrics || p.updateLyrics
      if (setter) {
        setter.call(p, lines)
        // 歌词更新后，重新计算布局并刷新显示帧
        p.calcLayout?.()
        p.update?.(0)
      } else {
        log('playerInstance does not expose lyric setter', 'warn')
      }
    }
  } catch (e) {
    log(`updateLyrics error: ${(e as Error).message}`, 'error')
  }
}

/**
 * 接收原生侧的低频时间锚点（约 5Hz），校准 WebView 内的本地时钟。
 * 只传位置与播放状态，不触发重绘；逐帧推进完全由 rAF 完成。
 */
window.syncPlaybackTime = (payload: any) => {
  const numeric = typeof payload === 'number'
  const positionMs = numeric ? payload : payload?.p
  if (!Number.isFinite(positionMs)) return
  const playing = numeric ? state.isPlaying : Boolean(payload?.s)
  const wallMs = numeric ? 0 : Number(payload?.w || 0)
  const hadState = state.hasPlaybackState
  const wasPlaying = state.isPlaying
  applyPlaybackState(playing)
  const playbackToggled = !hadState || wasPlaying !== playing

  const now = performance.now()
  if (!playbackClock.hasAnchor) {
    resetPlaybackClock(positionMs, playing, now)
    playbackClock.wallMs = wallMs
    return
  }

  // 原生仍在播放但上报的位置与上次相同 → MediaSession 采样未推进，忽略以免歌词回跳
  if (!playbackToggled && playing && positionMs === playbackClock.lastNativePositionMs) return
  playbackClock.lastNativePositionMs = positionMs
  playbackClock.wallMs = wallMs

  const predicted = getPlaybackTime(now)
  const error = positionMs - predicted
  if (playbackToggled || Math.abs(error) >= CLOCK_SEEK_THRESHOLD_MS) {
    resetPlaybackClock(positionMs, playing, now)
  } else if (Math.abs(error) >= CLOCK_MIN_CORRECTION_MS) {
    // 小误差平滑消化，避免每 200ms 一次可见跳动
    playbackClock.correctionMs = error
    playbackClock.correctionStartPerfMs = now
  }
}

/** 立即跳到指定时间（歌词行点击、外部 seek） */
window.seekPlaybackTime = (timeMs: number) => {
  if (!Number.isFinite(timeMs)) return
  const t = Math.trunc(timeMs)
  const p = state.player
  if (p) {
    try {
      // isSeek=true：让核心库重置滚动状态，避免从旧位置滑回来
      if (p.setCurrentTime) p.setCurrentTime(t, true)
      else if (p.seek) p.seek(t)
    } catch (e) {
      log(`seekPlaybackTime error: ${(e as Error).message}`, 'error')
    }
  }
  resetPlaybackClock(t, state.isPlaying)
}

/** 兼容旧接口：等同于 seek */
window.updateTime = (timeMs: number) => window.seekPlaybackTime?.(timeMs)

/**
 * 更新专辑封面
 * 支持 URL 或 base64，会自动处理 file:// 协议的本地文件
 */
window.updateAlbumArt = async (uri: string) => {
  if (!uri || uri.trim().length === 0) {
    state.albumUri = ''
    return
  }

  try {
    let finalUri = uri
    const isFileUri = uri.startsWith('file:')
    const isDataUri = uri.startsWith('data:')
    const isBlobUri = uri.startsWith('blob:')
    const isHttpUri = uri.startsWith('http://') || uri.startsWith('https://')

    // 如果是本地文件协议，尝试转换成 Data URL 以规避 WebView 的跨域限制
    if (isFileUri) {
      const response = await fetch(uri, { cache: 'no-store' })
      const blob = await response.blob()
      finalUri = await new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result as string)
        reader.onerror = () => reject(reader.error)
        reader.readAsDataURL(blob)
      })
    } else if (isHttpUri && !isDataUri && !isBlobUri) {
      const cacheBuster = `t=${Date.now()}`
      finalUri = uri.includes('?') ? `${uri}&${cacheBuster}` : `${uri}?${cacheBuster}`
    }

    state.albumUri = finalUri

    // 通知背景渲染器更新封面
    if (state.background?.setAlbum) {
      try {
        await state.background.setAlbum(state.albumUri)
        state.background.update?.(0)
      } catch (err) {
        log(`setAlbum error: ${(err as Error).message}. `, 'warn')
      }
    }
  } catch (e) {
    log(`updateAlbumArt error: ${(e as Error).message}`, 'error')
  }
}

/**
 * 控制播放/暂停
 */
window.setPaused = (paused: boolean) => {
  applyPlaybackState(!paused)
}

/**
 * 配置歌词动态效果
 * 包含弹性滚动、缩放、模糊等高级参数
 */
window.configureLyricMotion = (options: any) => {
  state.pendingLyricOptions = { ...state.pendingLyricOptions, ...options }
  const lp = state.player
  if (!lp) return

  try {
    const { springPosY, enableSpring, springScale, enableScale, enableBlur, hidePassedLines, wordFadeWidth } = options
    if (springPosY && lp.setLinePosYSpringParams) lp.setLinePosYSpringParams(springPosY)
    if (enableSpring !== undefined && lp.setEnableSpring) lp.setEnableSpring(enableSpring)
    if (springScale && lp.setLineScaleSpringParams) lp.setLineScaleSpringParams(springScale)
    if (enableScale !== undefined && lp.setEnableScale) lp.setEnableScale(enableScale)
    if (enableBlur !== undefined && lp.setEnableBlur) lp.setEnableBlur(enableBlur)
    if (hidePassedLines !== undefined && lp.setHidePassedLines) lp.setHidePassedLines(hidePassedLines)
    if (wordFadeWidth !== undefined && lp.setWordFadeWidth) lp.setWordFadeWidth(wordFadeWidth)
    lp.calcLayout?.()
    lp.update?.(0) // 刷新布局修改后的显示
  } catch (e) {
    log(`configureLyricMotion error: ${(e as Error).message}`, 'error')
  }
}

/**
 * 配置背景特效参数
 * 如流动速度、渲染缩放（性能优化）、帧率等
 */
window.configureBackgroundEffect = (options: any) => {
  const bg = state.background
  if (!bg) return
  try {
    if (options.flowSpeed !== undefined) bg.setFlowSpeed?.(options.flowSpeed)
    if (options.renderScale !== undefined) bg.setRenderScale?.(options.renderScale)
    if (options.lowFreqVolume !== undefined) bg.setLowFreqVolume?.(options.lowFreqVolume)
    if (options.fps !== undefined) bg.setFPS?.(options.fps)
    if (options.staticMode !== undefined) bg.setStaticMode?.(options.staticMode)
    bg.update?.(0) // 立即应用背景参数变更并重绘
  } catch (e) {
    log(`configureBackgroundEffect error: ${(e as Error).message}`, 'error')
  }
}

/**
 * 配置歌词底色背景
 * 可以在 Canvas 渲染背景和 CSS 纯色/渐变背景之间切换
 */
window.configureLyricBackground = (options: any) => {
  try {
    const isCssBg = options.renderer === 'css-bg'

    // 如果当前没有背景实例，且没有显式要求关闭(css-bg)，则根据 options.renderer 尝试初始化
    if (!state.background && !isCssBg) {
      state.background = createBackgroundRenderer(AMLLCore, document.getElementById('app')!, options.renderer)
    }

    const bgElement = state.background?.getElement?.()
    if (bgElement) {
      bgElement.style.display = isCssBg ? 'none' : 'block'
    }

    if (state.background) {
      const { fps, renderScale, staticMode } = options
      if (fps !== undefined) state.background.setFPS?.(fps)
      if (renderScale !== undefined) state.background.setRenderScale?.(renderScale)
      if (staticMode !== undefined) state.background.setStaticMode?.(staticMode)
      state.background.update?.(0)
    }

    document.body.style.background = (isCssBg && options.cssProperty) ? options.cssProperty : 'transparent'
  } catch (e) {
    log(`configureLyricBackground error: ${(e as Error).message}`, 'error')
  }
}

// --- CSS 变量设置项 (CSS Variable Setters) ---
window.setLyricSizePreset = (preset: string) => { if (preset !== undefined) setCSSVar('--amll-lp-font-size-preset', preset) }
window.setEnableTranslationLine = (enabled: boolean) => { if (enabled !== undefined) setCSSVar('--amll-show-translation', enabled) }
window.setEnableRomanLine = (enabled: boolean) => { if (enabled !== undefined) setCSSVar('--amll-show-roman', enabled) }
window.setEnableSwapTransRomanLine = (enabled: boolean) => { if (enabled !== undefined) setCSSVar('--amll-swap-trans-roman', enabled) }
window.setAdvanceLyricDynamicLyricTime = (enabled: boolean) => {
  if (enabled !== undefined) {
    setCSSVar('--amll-advance-dynamic-time', enabled)
    state.pendingLyricOptions.advanceDynamicTime = enabled
  }
}

/**
 * 应用字体设置：动态注入 @font-face 规则并更新 CSS 变量
 * @param config 包含 effectiveFamily（字体栈）和 files（字体文件列表）
 */
window.applyFontSettings = (config: any) => {
  const { effectiveFamily, files = [] } = config || {}

  // 创建或更新动态 font-face 样式元素
  const styleId = 'amll-dynamic-font-face-style'
  let styleNode = document.getElementById(styleId) as HTMLStyleElement | null
  if (!styleNode) {
    styleNode = document.createElement('style')
    styleNode.id = styleId
    document.head.appendChild(styleNode)
  }

  // 构建 @font-face 规则
  let css = ''
  if (Array.isArray(files)) {
    for (const item of files) {
      if (!item?.familyName || !item?.uri) continue
      if (item.uri.startsWith('data:image/svg+xml')) continue
      css += `@font-face{font-family:"${item.familyName}";src:url("${item.uri}");font-display:swap;}`
    }
  }
  styleNode.textContent = css

  // 应用 CSS 变量
  setCSSVar('--amll-user-font-family', effectiveFamily || '')
  setCSSVar('--amll-lp-font-family', effectiveFamily ? 'var(--amll-user-font-family)' : '')

  // 应用到歌词播放器元素
  const players = document.querySelectorAll('.amll-lyric-player')
  players.forEach((el) => {
    ;(el as HTMLElement).style.fontFamily = effectiveFamily ? 'var(--amll-lp-font-family)' : ''
  })
}

// --- 其他占位或转发接口 ---
window.setRenderMode = (mode: string) => log(`setRenderMode: ${mode}`, 'debug')
window.setLyricPlayerImplementation = (imp: string) => log(`setLyricPlayerImplementation: ${imp}`, 'debug')
window.rebuildLyricsDom = (reason?: string) => {
  log(`rebuildLyricsDom: ${reason}`, 'debug')
  state.player?.calcLayout?.()
  state.player?.update?.(0)
  return true
}