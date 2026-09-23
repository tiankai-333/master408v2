/**
 * 双端账户共存的浏览器层验证（真实 Chromium + CDP，无新增依赖）。
 *
 * 与 verify-dual-client.ps1 的区别：这里使用真实浏览器内核、真实 Cookie 策略和真实前端页面，
 * 因此同时验证“浏览器确实按主机共享 Cookie（忽略端口）”这一根因前提，以及修复后的双端隔离。
 *
 * 用法：
 *   $env:M408_TEST_PASSWORD='<测试账户口令>'; node verify-dual-client-browser.mjs
 *
 * 依赖：本机已安装 Node 18+（需要内置 WebSocket）与 Playwright 缓存中的 Chromium，
 * 或通过 CHROME_PATH 指定 chrome.exe 路径。脚本不新增任何 npm 依赖。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const STUDENT_BASE = process.env.M408_STUDENT_BASE ?? 'http://localhost:8001'
const ADMIN_BASE = process.env.M408_ADMIN_BASE ?? 'http://localhost:8002'
const STUDENT_USER = process.env.M408_STUDENT_USER ?? 'student'
const ADMIN_USER = process.env.M408_ADMIN_USER ?? 'admin'
const PASSWORD = process.env.M408_TEST_PASSWORD
const DEBUG_PORT = Number(process.env.M408_CDP_PORT ?? 9333)

if (!PASSWORD) {
  console.error('缺少 M408_TEST_PASSWORD 环境变量（口令不写入仓库）')
  process.exit(2)
}

const candidateChrome = [
  process.env.CHROME_PATH,
  join(process.env.LOCALAPPDATA ?? '', 'ms-playwright', 'chromium-1223', 'chrome-win64', 'chrome.exe'),
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
].filter(Boolean)
const chromePath = candidateChrome.find(path => existsSync(path))
if (!chromePath) {
  console.error('未找到 Chromium/Chrome，可用 CHROME_PATH 指定')
  process.exit(2)
}

let checks = 0
let failures = 0
const results = []

function check (label, actual, expected) {
  checks++
  const ok = typeof expected === 'function' ? expected(actual) : String(actual) === String(expected)
  if (!ok) failures++
  results.push({ label, ok, actual, expected: typeof expected === 'function' ? '(predicate)' : expected })
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label.padEnd(56)} -> ${JSON.stringify(actual)}`)
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

class Cdp {
  constructor (ws) {
    this.ws = ws
    this.nextId = 1
    this.pending = new Map()
    this.waiters = []
    ws.addEventListener('message', event => {
      const message = JSON.parse(event.data)
      if (message.id && this.pending.has(message.id)) {
        const { resolve, reject } = this.pending.get(message.id)
        this.pending.delete(message.id)
        message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result)
        return
      }
      this.waiters = this.waiters.filter(waiter => {
        if (waiter.method === message.method && (!waiter.sessionId || waiter.sessionId === message.sessionId)) {
          waiter.resolve(message.params)
          return false
        }
        return true
      })
    })
  }

  send (method, params = {}, sessionId) {
    const id = this.nextId++
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }))
    })
  }

  waitFor (method, sessionId, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      const waiter = { method, sessionId, resolve }
      this.waiters.push(waiter)
      setTimeout(() => {
        this.waiters = this.waiters.filter(candidate => candidate !== waiter)
        reject(new Error(`等待 ${method} 超时`))
      }, timeoutMs)
    })
  }
}

const userDataDir = mkdtempSync(join(tmpdir(), 'm408-browser-'))
const chrome = spawn(chromePath, [
  '--headless=new',
  '--disable-gpu',
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-extensions',
  `--remote-debugging-port=${DEBUG_PORT}`,
  `--user-data-dir=${userDataDir}`,
  'about:blank'
], { stdio: 'ignore' })

let cdp
let sessionId

async function connect () {
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`)
      const version = await response.json()
      const ws = new WebSocket(version.webSocketDebuggerUrl)
      await new Promise((resolve, reject) => {
        ws.addEventListener('open', resolve, { once: true })
        ws.addEventListener('error', reject, { once: true })
      })
      cdp = new Cdp(ws)
      const target = await cdp.send('Target.createTarget', { url: 'about:blank' })
      const attached = await cdp.send('Target.attachToTarget', { targetId: target.targetId, flatten: true })
      sessionId = attached.sessionId
      await cdp.send('Page.enable', {}, sessionId)
      await cdp.send('Runtime.enable', {}, sessionId)
      await cdp.send('Network.enable', {}, sessionId)
      return
    } catch {
      await sleep(300)
    }
  }
  throw new Error('无法连接 Chromium 调试端口')
}

async function navigate (url) {
  const loaded = cdp.waitFor('Page.loadEventFired', sessionId)
  await cdp.send('Page.navigate', { url }, sessionId)
  await loaded
  await sleep(500)
}

async function reload () {
  const loaded = cdp.waitFor('Page.loadEventFired', sessionId)
  await cdp.send('Page.reload', {}, sessionId)
  await loaded
  await sleep(500)
}

async function evaluate (expression) {
  const result = await cdp.send('Runtime.evaluate', {
    expression, awaitPromise: true, returnByValue: true
  }, sessionId)
  if (result.exceptionDetails) {
    throw new Error(`页面执行失败：${JSON.stringify(result.exceptionDetails.exception?.description ?? result.exceptionDetails)}`)
  }
  return result.result.value
}

async function apiIdentity () {
  return evaluate(`fetch('/api/student/user/current', {method:'POST', credentials:'include', headers:{'Content-Type':'application/json'}}).then(r=>r.json()).then(j=>({code:j.code, userName:j.response && j.response.userName}))`)
}

async function apiIdentityOn (base, path) {
  await navigate(base)
  return evaluate(`fetch(${JSON.stringify(path)}, {method:'POST', credentials:'include', headers:{'Content-Type':'application/json'}}).then(r=>r.json()).then(j=>({code:j.code, userName:j.response && j.response.userName}))`)
}

async function apiCall (path, body) {
  // fetch 的 body 必须是字符串，直接传对象会被强转成 "[object Object]" 并被后端判为口令错误。
  const payload = body ? JSON.stringify(JSON.stringify(body)) : 'undefined'
  return evaluate(`fetch(${JSON.stringify(path)}, {method:'POST', credentials:'include', headers:{'Content-Type':'application/json'}, body:${payload}}).then(r=>r.json())`)
}

async function formLogin (base, userName) {
  await navigate(`${base}/login`)
  await evaluate(`(() => {
    const setValue = (el, value) => {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
      setter.call(el, value)
      el.dispatchEvent(new Event('input', { bubbles: true }))
      el.dispatchEvent(new Event('change', { bubbles: true }))
    }
    setValue(document.querySelector('input[name="userName"]'), ${JSON.stringify(userName)})
    setValue(document.querySelector('input[name="password"]'), ${JSON.stringify(PASSWORD)})
    return true
  })()`)
  await sleep(300)
  await evaluate(`document.querySelector('.login-btn').click()`)
  const deadline = Date.now() + 20000
  while (Date.now() < deadline) {
    const path = await evaluate('location.pathname')
    if (path !== '/login') return path
    await sleep(400)
  }
  return await evaluate('location.pathname')
}

async function allCookies () {
  const { cookies } = await cdp.send('Network.getAllCookies', {}, sessionId)
  return cookies.map(cookie => `${cookie.name}@${cookie.domain}${cookie.path}`)
}

try {
  await connect()
  console.log(`\n浏览器：${chromePath}`)
  console.log(`环境：student=${STUDENT_BASE} admin=${ADMIN_BASE}\n`)

  console.log('== 界面登录：真实前端表单（学生端 :8001）==')
  const studentPath = await formLogin(STUDENT_BASE, STUDENT_USER)
  check('学生端表单登录后离开登录页', studentPath, path => path !== '/login')
  check('学生端身份查询', await apiIdentity(), result => result.code === 1 && result.userName === 'student')

  console.log('\n== 界面登录：真实前端表单（管理端 :8002，同一浏览器）==')
  const adminPath = await formLogin(ADMIN_BASE, ADMIN_USER)
  check('管理端表单登录后离开登录页', adminPath, path => path !== '/login')
  check('管理端身份查询', await apiIdentityOn(ADMIN_BASE, '/api/admin/user/current'),
    result => result.code === 1 && result.userName === 'admin')

  console.log('\n== AUTH-01：回到学生端页面并刷新，身份不被管理端覆盖 ==')
  await navigate(`${STUDENT_BASE}/`)
  await reload()
  check('刷新后学生端仍为学生', await apiIdentity(), result => result.code === 1 && result.userName === 'student')
  check('管理端同时仍为管理员', await apiIdentityOn(ADMIN_BASE, '/api/admin/user/current'),
    result => result.code === 1 && result.userName === 'admin')

  console.log('\n== 真实浏览器 Cookie 证据 ==')
  const cookies = await allCookies()
  const sessionCookies = cookies.filter(name => name.startsWith('JSESSIONID'))
  check('浏览器按主机共享单一 JSESSIONID（端口不参与匹配）', sessionCookies.length,
    count => count === 1)
  check('学生端展示缓存 Cookie 存在', cookies.some(name => name.startsWith('studentUserName')), true)
  check('管理端展示缓存 Cookie 存在', cookies.some(name => name.startsWith('adminUserName')), true)

  console.log('\n== AUTH-02：学生端退出不影响管理端（真实浏览器请求）==')
  await navigate(`${STUDENT_BASE}/`)
  check('学生端退出', await apiCall('/api/student/logout'), body => body.code === 1)
  check('学生端已退出', await apiIdentity(), result => result.code === 401)
  check('管理端不受影响', await apiIdentityOn(ADMIN_BASE, '/api/admin/user/current'),
    result => result.code === 1 && result.userName === 'admin')

  console.log('\n== AUTH-05：remember-me 凭据按端隔离（真实浏览器）==')
  const rememberLogin = await apiCall('/api/student/login', { userName: STUDENT_USER, password: PASSWORD, remember: true })
  check('学生端勾选记住密码登录', rememberLogin, body => body.code === 1)
  check('签发学生端专属凭据', (await allCookies()).some(name => name.startsWith('remember-me-student')), true)
  await cdp.send('Network.deleteCookies', { name: 'JSESSIONID', domain: 'localhost', path: '/' }, sessionId)
  check('删除会话 Cookie 后学生端凭据恢复学生', await apiIdentity(), result => result.code === 1 && result.userName === 'student')
  check('学生端凭据不能恢复管理端', await apiIdentityOn(ADMIN_BASE, '/api/admin/user/current'),
    result => result.code === 401)
} catch (error) {
  failures++
  console.error(`\n浏览器验证中断：${error.message}`)
} finally {
  try {
    if (cdp) await cdp.send('Browser.close')
  } catch { /* 忽略关闭错误 */ }
  chrome.kill()
  await sleep(500)
  rmSync(userDataDir, { recursive: true, force: true })
  console.log(`\n汇总：${checks} 项检查，${failures} 项失败`)
  process.exit(failures > 0 ? 1 : 0)
}
