/**
 * Vite 构建配置文件
 * 
 * Vite 是一个现代的前端构建工具，提供快速的开发服务器和优化的生产构建。
 * 这个配置文件定义了如何打包 AMLL React 前端应用，以便嵌入到 Android WebView 中使用。
 * 
 * 主要配置项：
 * - React 插件支持 JSX/TSX
 * - IIFE 格式输出（适合直接在 HTML 中使用）
 * - CSS 内联处理
 * - Source Map 生成（便于调试）
 */
import { defineConfig } from 'vite'
import { resolve } from 'node:path'
import wasm from "vite-plugin-wasm";
import topLevelAwait from "vite-plugin-top-level-await";

/**
 * 自定义简单 CSS 内联插件
 * 捕获生成的 CSS 并将其注入到 JS 束中
 */
const cssInliner = () => {
  return {
    name: 'css-inliner',
    apply: 'build',
    enforce: 'post',
    generateBundle(options, bundle) {
      const cssFiles = Object.keys(bundle).filter(file => file.endsWith('.css'))
      const jsFiles = Object.keys(bundle).filter(file => file.endsWith('.js'))

      let cssContent = ''
      cssFiles.forEach(file => {
        cssContent += bundle[file].source + '\n'
        delete bundle[file] // 从输出中移除独立的 CSS 文件
      })

      if (cssContent && jsFiles.length > 0) {
        // 默认注入到第一个 JS 文件（在库模式下通常只有一个 bundle）
        const jsFile = jsFiles[0]
        const jsContent = bundle[jsFile].code
        const injectedJs = `(function(){if(typeof document!=='undefined'){const style=document.createElement('style');style.innerText=${JSON.stringify(cssContent)};document.head.appendChild(style);}})();\n${jsContent}`
        bundle[jsFile].code = injectedJs
      }
    }
  }
}

// 使用 defineConfig 定义配置，获得更好的 TypeScript 类型提示
export default defineConfig({
  // ==================== 插件配置 ====================
  // 本工程在 frontend/src/main.tsx 使用的是 AMLL core API（无 React 挂载），
  // 因此移除 React 插件可以避免把 React 相关运行时打包进最终 bundle。
  plugins: [
    wasm(),
    topLevelAwait(),
    cssInliner()
  ],
  // ==================== 基础路径 ====================
  // 设置资源引用的基础路径为相对路径 './'
  // 这样在 Android WebView 中加载时可以正确找到资源
  base: './',
  // ==================== 全局变量定义 ====================
  // 定义全局常量，确保在不同环境下的兼容性
  define: {
    global: 'globalThis',  // 将 global 映射到 globalThis（标准的全局对象）
    'process.env.NODE_ENV': JSON.stringify('development'),  // 设置 Node 环境变量为开发模式
  },
  // ==================== 构建设置 ====================
  build: {
    outDir: 'dist',  // 构建产物输出目录
    emptyOutDir: true,  // 构建前清空输出目录
    sourcemap: true,  // 生成 Source Map 文件（便于调试）
    cssCodeSplit: false,  // 不拆分 CSS 文件，保持单一 CSS 文件
    minify: false,  // 不压缩代码（保留可读性，便于调试）
    
    // 库模式配置：打包为独立的库文件
    lib: {
      entry: resolve(import.meta.dirname, 'src/main.tsx'),  // 入口文件
      name: 'AMLL',
      formats: ['es'],  // 改用 ES 模块格式以支持 Wasm 和 Top-level await
      fileName: () => 'amll.bundle.js',  // 输出文件名（固定为 amll.bundle.js）
    },
    // Ensure CSS is handled - note: in lib mode Vite normally outputs a separate CSS file.
    // To fix @/frontend/src/styles.css not being bundled into the JS, we might need a plugin.
    // But first, let's ensure the rollup options don't exclude it.
    rollupOptions: {
      output: {
        codeSplitting: false,
      },
      // no externalization for AMLL core
      external: []
    },
    // Ensure CommonJS packages (and local workspace packages) are processed
    commonjsOptions: {
      include: [/node_modules/, /@applemusic-like-lyrics/]
    },
  },
  // ==================== 模块解析配置 ====================
  resolve: {
    // 不再需要对 react/react-dom 去重（本项目不使用 React 运行时）
  },
  // ==================== 开发服务器配置 ====================
  server: {
    port: 5173,  // 开发服务器监听的端口号
  },
})
