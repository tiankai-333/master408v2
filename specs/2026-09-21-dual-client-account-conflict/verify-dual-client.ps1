#!/usr/bin/env pwsh
<#
.SYNOPSIS
    双端账户共存回归验证：同一浏览器（共享 Cookie 容器）中，学生端与管理端各自保持身份。

.DESCRIPTION
    按 validation.md 的 AUTH-01/02/03/05 场景，通过两个前端 dev server 端口（默认 8001/8002）
    访问真实后端，仅使用 HTTP 集成层证据。每个场景使用一个独立的 Cookie 容器；容器内不区分端口，
    与浏览器“Cookie 按主机共享、忽略端口”的行为一致。

    脚本不写入、不修改任何账户数据；密码由调用方通过参数提供，不保存在仓库中。

.EXAMPLE
    pwsh specs/2026-09-21-dual-client-account-conflict/verify-dual-client.ps1 -Password <测试账户口令>
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$StudentBase = 'http://localhost:8001',
    [string]$AdminBase = 'http://localhost:8002',
    [string]$StudentUser = 'student',
    [string]$AdminUser = 'admin'
)

$ErrorActionPreference = 'Stop'
$script:Failures = 0
$script:Checks = 0
$work = Join-Path $env:TEMP ("m408-verify-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null

function New-Body {
    param([string]$Name, [string]$UserName, [string]$Secret, [bool]$Remember = $false)
    $json = '{"userName":"' + $UserName + '","password":"' + $Secret + '","remember":' + $Remember.ToString().ToLower() + '}'
    $path = Join-Path $work $Name
    [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

function New-Jar {
    param([string]$Name)
    $path = Join-Path $work $Name
    if (Test-Path $path) { Remove-Item $path -Force }
    return $path
}

function Invoke-Api {
    param([string]$Jar, [string]$Base, [string]$Path, [string]$BodyFile)
    $arguments = @('-s', '-i', '-c', $Jar, '-b', $Jar, '-X', 'POST', "$Base$Path",
        '-H', 'Content-Type: application/json', '-H', 'request-ajax: true')
    if ($BodyFile) { $arguments += @('--data-binary', "@$BodyFile") }
    $raw = & curl.exe @arguments
    $body = ($raw | Select-Object -Last 1)
    $code = -1
    if ($body -match '"code":(-?\d+)') { $code = [int]$Matches[1] }
    $identity = $null
    if ($body -match '"userName":"([^"]+)"') { $identity = $Matches[1] }
    return [pscustomobject]@{
        Status      = (($raw | Select-String -Pattern '^HTTP/').Line -replace 'HTTP/1\.1 ', '')
        CookieNames = @(($raw | Select-String -Pattern '^set-cookie') | ForEach-Object { (($_.Line -replace '^set-cookie: ', '') -split '=')[0] })
        Code        = $code
        Identity    = $identity
        Body        = $body
    }
}

function Assert-Code {
    param([string]$Label, $Result, [int]$Expected)
    $script:Checks++
    if ($Result.Code -eq $Expected) {
        Write-Host ("  PASS  {0,-56} -> code={1}" -f $Label, $Result.Code)
    } else {
        $script:Failures++
        Write-Host ("  FAIL  {0,-56} -> 实际 code={1}，期望 {2}；响应 {3}" -f $Label, $Result.Code, $Expected, $Result.Body) -ForegroundColor Red
    }
}

function Assert-Identity {
    param([string]$Label, $Result, [string]$Expected)
    $script:Checks++
    if ($Result.Identity -eq $Expected) {
        Write-Host ("  PASS  {0,-56} -> 身份={1}" -f $Label, $Result.Identity)
    } else {
        $script:Failures++
        Write-Host ("  FAIL  {0,-56} -> 实际 身份={1}，期望 {2}；响应 {3}" -f $Label, $Result.Identity, $Expected, $Result.Body) -ForegroundColor Red
    }
}

function Assert-Contains {
    param([string]$Label, [string]$Actual, [string]$Expected)
    $script:Checks++
    $shown = $Actual
    if ($shown.Length -gt 70) { $shown = $shown.Substring(0, 70) + '...' }
    if ($Actual -like "*$Expected*") {
        Write-Host ("  PASS  {0,-56} -> {1}" -f $Label, $shown)
    } else {
        $script:Failures++
        Write-Host ("  FAIL  {0,-56} -> 实际 {1}，期望包含 {2}" -f $Label, $shown, $Expected) -ForegroundColor Red
    }
}

function Remove-SessionCookie {
    param([string]$Jar)
    $kept = Get-Content $Jar | Where-Object { $_ -notmatch 'JSESSIONID' }
    Set-Content -Path $Jar -Value $kept -Encoding ascii
}

function New-RawBody {
    param([string]$Name, [string]$Json)
    $path = Join-Path $work $Name
    [System.IO.File]::WriteAllText($path, $Json, (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

function Invoke-Stream {
    param([string]$Jar, [string]$Base, [string]$Path, [string]$BodyFile)
    $raw = & curl.exe -s -i -N --max-time 30 -c $Jar -b $Jar -X POST "$Base$Path" `
        -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -H 'request-ajax: true' `
        --data-binary "@$BodyFile"
    return [pscustomobject]@{
        Status = (($raw | Select-String -Pattern '^HTTP/').Line -replace 'HTTP/1\.1 ', '')
        Text   = ($raw -join "`n")
    }
}

$studentBody = New-Body 'student.json' $StudentUser $Password
$adminBody = New-Body 'admin.json' $AdminUser $Password
$studentRememberBody = New-Body 'student-remember.json' $StudentUser $Password $true
$adminRememberBody = New-Body 'admin-remember.json' $AdminUser $Password $true
$studentWrongBody = New-Body 'student-wrong.json' $StudentUser 'not-the-password'
$emptyQuestionBody = New-RawBody 'empty-question.json' '{"questionType":"single","questionContent":"","style":"default"}'

Write-Host ''
Write-Host '== AUTH-01：先学生端 S，再管理端 A（同一浏览器共享 Cookie 容器） =='
$jar = New-Jar 'auth01a.txt'
Assert-Code 'S 登录学生端 :8001 /api/student/login' (Invoke-Api $jar $StudentBase '/api/student/login' $studentBody) 1
Assert-Identity 'S 学生端身份查询' (Invoke-Api $jar $StudentBase '/api/student/user/current') 'student'
Assert-Code 'A 登录管理端 :8002 /api/admin/login（同一容器）' (Invoke-Api $jar $AdminBase '/api/admin/login' $adminBody) 1
Assert-Identity 'A 管理端身份查询' (Invoke-Api $jar $AdminBase '/api/admin/user/current') 'admin'
Assert-Identity 'S 学生端身份查询仍为学生（AUTH-01 关键）' (Invoke-Api $jar $StudentBase '/api/student/user/current') 'student'

Write-Host ''
Write-Host '== AUTH-01：先管理端 A，再学生端 S =='
$jar = New-Jar 'auth01b.txt'
Assert-Code 'A 登录管理端' (Invoke-Api $jar $AdminBase '/api/admin/login' $adminBody) 1
Assert-Code 'S 登录学生端（同一容器）' (Invoke-Api $jar $StudentBase '/api/student/login' $studentBody) 1
Assert-Identity 'A 管理端身份查询仍为管理员（AUTH-01 关键）' (Invoke-Api $jar $AdminBase '/api/admin/user/current') 'admin'
Assert-Identity 'S 学生端身份查询' (Invoke-Api $jar $StudentBase '/api/student/user/current') 'student'

Write-Host ''
Write-Host '== AUTH-02：单端退出、单端登录失败互不影响 =='
$jar = New-Jar 'auth02.txt'
Assert-Code 'S 登录学生端' (Invoke-Api $jar $StudentBase '/api/student/login' $studentBody) 1
Assert-Code 'A 登录管理端' (Invoke-Api $jar $AdminBase '/api/admin/login' $adminBody) 1
Assert-Code 'S 学生端退出 :8001 /api/student/logout' (Invoke-Api $jar $StudentBase '/api/student/logout') 1
Assert-Code 'S 学生端已退出' (Invoke-Api $jar $StudentBase '/api/student/user/current') 401
Assert-Identity 'A 管理端身份不受 S 退出影响（AUTH-02 关键）' (Invoke-Api $jar $AdminBase '/api/admin/user/current') 'admin'

$jar = New-Jar 'auth02b.txt'
Assert-Code 'A 登录管理端' (Invoke-Api $jar $AdminBase '/api/admin/login' $adminBody) 1
Assert-Code 'S 用错误口令登录学生端' (Invoke-Api $jar $StudentBase '/api/student/login' $studentWrongBody) 402
Assert-Identity 'A 管理端身份不受 S 登录失败影响' (Invoke-Api $jar $AdminBase '/api/admin/user/current') 'admin'

Write-Host ''
Write-Host '== AUTH-03：学生会话不能访问管理接口 =='
$jar = New-Jar 'auth03.txt'
Assert-Code 'S 登录学生端' (Invoke-Api $jar $StudentBase '/api/student/login' $studentBody) 1
Assert-Code 'S 直接请求管理接口被服务端拒绝' (Invoke-Api $jar $StudentBase '/api/admin/user/current') 401
Assert-Identity 'S 学生端自身仍正常' (Invoke-Api $jar $StudentBase '/api/student/user/current') 'student'

Write-Host ''
Write-Host '== AUTH-05：remember-me 凭据按端隔离 =='
$jar = New-Jar 'auth05s.txt'
$studentLogin = Invoke-Api $jar $StudentBase '/api/student/login' $studentRememberBody
Assert-Contains 'S 勾选记住密码登录，签发端专属凭据' ($studentLogin.CookieNames -join ',') 'remember-me-student'
Remove-SessionCookie $jar
Assert-Identity '会话过期后学生端凭据恢复学生身份' (Invoke-Api $jar $StudentBase '/api/student/user/current') 'student'
Assert-Code '学生端凭据不能用于管理端' (Invoke-Api $jar $AdminBase '/api/admin/user/current') 401

$jar = New-Jar 'auth05a.txt'
$adminLogin = Invoke-Api $jar $AdminBase '/api/admin/login' $adminRememberBody
Assert-Contains 'A 勾选记住密码登录，签发端专属凭据' ($adminLogin.CookieNames -join ',') 'remember-me-admin'
Remove-SessionCookie $jar
Assert-Identity '会话过期后管理端凭据恢复管理员身份' (Invoke-Api $jar $AdminBase '/api/admin/user/current') 'admin'
Assert-Code '管理端凭据不能用于学生端' (Invoke-Api $jar $StudentBase '/api/student/user/current') 401

Write-Host ''
Write-Host '== AUTH-06：流式（SSE）请求使用本端身份，且不被另一端影响 =='
$jar = New-Jar 'sse.txt'
Assert-Code 'S 登录学生端' (Invoke-Api $jar $StudentBase '/api/student/login' $studentBody) 1
Assert-Code 'A 登录管理端（同一容器）' (Invoke-Api $jar $AdminBase '/api/admin/login' $adminBody) 1
$stream = Invoke-Stream $jar $StudentBase '/api/student/question/analyze-question-stream' $emptyQuestionBody
Assert-Contains '流式接口以学生端身份执行（返回事件流）' $stream.Text '题目内容不能为空'
$anonymousStream = Invoke-Stream (New-Jar 'sse-anon.txt') $StudentBase '/api/student/question/analyze-question-stream' $emptyQuestionBody
Assert-Contains '匿名流式请求被拒绝' $anonymousStream.Text '"code":401'

Write-Host ''
Write-Host '== 兼容：旧公共入口 /api/user/login 仍按角色进入对应端 =='
$jar = New-Jar 'legacy.txt'
Assert-Code '旧入口以学生身份登录' (Invoke-Api $jar $StudentBase '/api/user/login' $studentBody) 1
Assert-Identity '学生端可继续使用该身份' (Invoke-Api $jar $StudentBase '/api/student/user/current') 'student'
Assert-Code '该身份不能访问管理端' (Invoke-Api $jar $AdminBase '/api/admin/user/current') 401

Write-Host ''
Write-Host '== 兼容：旧公共退出入口保留整体退出语义（会清除本会话全部端身份） =='
$jar = New-Jar 'legacy-logout.txt'
Assert-Code 'S 登录学生端' (Invoke-Api $jar $StudentBase '/api/student/login' $studentBody) 1
Assert-Code 'A 登录管理端' (Invoke-Api $jar $AdminBase '/api/admin/login' $adminBody) 1
Assert-Code '旧入口退出 :8001 /api/user/logout' (Invoke-Api $jar $StudentBase '/api/user/logout') 1
Assert-Code 'S 学生端已退出' (Invoke-Api $jar $StudentBase '/api/student/user/current') 401
Assert-Code 'A 管理端同样已退出（旧入口语义）' (Invoke-Api $jar $AdminBase '/api/admin/user/current') 401

Write-Host ''
Write-Host ("汇总：{0} 项检查，{1} 项失败" -f $script:Checks, $script:Failures)
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
if ($script:Failures -gt 0) { exit 1 }
exit 0
