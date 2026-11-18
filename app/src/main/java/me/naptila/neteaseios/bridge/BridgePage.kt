package me.naptila.neteaseios.bridge

import java.util.Locale

/** Pure page renderer executed inside the target game's GMWebviewActivity. */
internal object BridgePage {
    fun render(
        appLabel: String,
        base: String,
        compatibilityMode: Boolean
    ): String {
        // Full recovery and compatibility instructions live in the assistant.
        // The game-side page should only report the current technical stage.
        val guidance = if (compatibilityMode) {
            "兼容模式请求已提交，SDK 结果未确认。请返回游戏核对角色，详细操作见助手。"
        } else {
            "iOS 登录准备请求已提交，SDK 结果未确认。请返回游戏核对角色，详细操作见助手。"
        }
        val badgeText = escapeHtml("网易 iOS 登录桥接 · $appLabel")

        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>")
            append("body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f3f6fc;color:#17263d;font:15px/1.5 -apple-system,BlinkMacSystemFont,sans-serif;padding:16px;box-sizing:border-box}")
            append(".card{max-width:320px;width:100%;background:#fff;border-radius:18px;padding:20px;box-shadow:0 12px 36px rgba(20,33,61,.12);border:1px solid #dbe4f5;box-sizing:border-box}")
            append(".badge{display:inline-block;padding:4px 9px;border-radius:999px;background:#edf3fd;color:#2457a5;font-size:11px;font-weight:600;margin-bottom:10px}")
            append("h2{margin:0 0 9px;font-size:19px;color:#0f172a}")
            append("#status{margin:0 0 16px;color:#475569;font-size:13px;line-height:1.65}")
            append(".help{margin:0 0 15px;padding:9px 11px;border-radius:10px;background:#f1f5fb;border:1px solid #d9e2ef;color:#495b78;font-size:12px;line-height:1.6}.help summary{cursor:pointer;font-weight:600}.help p{margin:7px 0 0}")
            append("button{appearance:none;border:0;border-radius:11px;background:#26529b;color:#fff;font-size:14px;font-weight:600;padding:11px 16px;flex:1;cursor:pointer}")
            append(".actions{display:flex;gap:9px}.secondary{background:#e8effa;color:#2457a5}")
            append("</style></head><body>")
            append("<div class=\"card\">")
            append("<span class=\"badge\">").append(badgeText).append("</span>")
            append("<h2>准备 iOS 登录</h2>")
            append("<p id=\"status\">正在检测游戏登录状态…</p>")
            append("<details class=\"help\"><summary>登录说明及注意事项</summary><p>按游戏实际显示的「重新登录」或「切换账号」入口操作；旧弹窗需先关闭再重开。SDK 未确认时不能判定 iOS 登录成功。详细阶段日志请返回助手查看。</p></details>")
            append("<div class=\"actions\"><button class=\"secondary\" onclick=\"window.ntesOpenAssistant()\">返回助手</button><button onclick=\"window.ntesBridgeClose()\">返回游戏</button></div>")
            append("</div>")
            append("<script>(function(){console.log=function(){};window.ntesBridgeClose=userClose;window.ntesOpenAssistant=openAssistant;")
            append("var complete=false,sending=false,tries=0,importing=false,callbackSeen=false;")
            append("var base=").append(quote(base)).append(";")
            append("function show(s){document.getElementById('status').textContent=s;}")
            append("function closePage(){AndroidJSBridge.postMsgToNative(JSON.stringify({methodId:'navigation_bar_func',reqData:{methodId:'close',action:'close'}}));}")
            append("function userClose(){if(importing&&!complete){finishImport(false,'manual_return');return;}closePage();}")
            append("function openAssistant(){show('正在尝试打开助手…');post('/return-assistant',{}).then(function(){show('已请求打开助手。如未跳转，请通过最近任务打开助手。');}).catch(function(){show('无法自动打开助手，请通过最近任务打开助手查看详情。');});}")
            append("function post(route,j){return fetch(base+route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(j)}).then(function(r){if(!r.ok)throw Error();return r.json();});}")
            append("function fail(reason,text){if(complete)return;complete=true;show(text+' 可点「返回助手」查看详情，或通过最近任务打开。');post('/failed',{reason:reason}).catch(function(){});}")
            append("function finishImport(confirmed,reason){if(complete)return;complete=true;show(confirmed?'SDK 已确认加载请求，请回游戏核对角色。':").append(quote(guidance)).append(");post('/submitted',{confirmed:confirmed,reason:String(reason||'unknown')}).then(function(){setTimeout(closePage,900);}).catch(function(){show('准备结果未能回传助手，请返回游戏核对登录结果。');});}")
            append("function receiveImport(raw){if(complete||!importing)return;if(!callbackSeen){callbackSeen=true;post('/stage',{event:'sdk_callback_seen'}).catch(function(){});}try{var j=typeof raw==='string'?JSON.parse(raw):raw;if(!j)return;if(j.errCode===0||j.errCode==='0'){finishImport(true,'sdk_confirmed');return;}if(j.errCode!==undefined&&Number(j.errCode)!==0){var m=String(j.errMsg||'');fail(m==='File content is empty'?'sdk_read_failed':m==='No valid login info found'?'sdk_invalid_session':'sdk_rejected','SDK 未接受本次准备，请返回助手查看结果。');}}catch(e){}}")
            append("function receive(raw){if(complete||sending||importing)return;try{var j=typeof raw==='string'?JSON.parse(raw):raw;if(!j||j.executed_methodId!=='gameContextInfo')return;")
            append("sending=true;show('正在核验游戏账号…');var context={full:String(j.user_name||''),game:String(j.game_id||''),channel:String(j.app_channel||'')};post('/check',context).then(function(check){if(complete)return null;if(!check.ok){var r=String(check.reason||'check_failed');fail(r,r==='not_logged_in'?'请先在游戏内登录账号。':'游戏账号检查失败，请重新登录后重试。');return null;}show('已检测到账号，正在准备 iOS 数据…');return post('/prepare',context);}).then(function(v){")
            append("if(complete||!v)return;if(!v.ok||!v.command){var r=String(v.reason||'prepare_failed');fail(r,r==='context_changed'?'游戏账号状态已变化，请重新检测。':'准备未完成，请返回助手查看原因。');return;}")
            append("importing=true;var cmd=v.command;cmd.callback={nativeCallback:receiveImport};try{window.postMsgToNative(cmd);post('/stage',{event:'load_dispatched'}).catch(function(){});}catch(e){importing=false;fail('load_not_dispatched','准备文件已写入，但游戏网页桥未能提交 SDK 加载请求。');return;}show('准备文件已写入，已调用游戏 SDK；正在等待回调…');setTimeout(function(){if(!complete&&importing)show('暂未收到可确认的 SDK 回调。可继续等待，或点「返回游戏」自行核对角色。');},12000);setTimeout(function(){if(!complete&&importing)finishImport(false,callbackSeen?'unrecognized_callback':'callback_timeout');},30000);")
            append("}).catch(function(){fail('request_failed','操作未完成，请返回助手重试。');}).finally(function(){sending=false;});")
            append("raw=null;j=null;}catch(e){fail('invalid_response','游戏客户端返回格式不兼容，请返回助手。');}}")
            append("function start(){if(complete||importing)return;if(typeof window.postMsgToNative!=='function'||!window.NTCallBackList){if(++tries<30)setTimeout(start,250);else fail('bridge_missing','连接未就绪，请先打开游戏登录一次。');return;}")
            append("window.postMsgToNative({methodId:'execute_extend_func',reqData:{methodId:'gameContextInfo',callback_mode:'web'},callback:{nativeCallback:receive}});")
            append("if(++tries<8)setTimeout(start,900);else setTimeout(function(){if(!complete&&!importing)fail('no_callback','未收到游戏接口响应，请返回助手重试。');},1800);}")
            append("window.addEventListener('load',function(){setTimeout(start,700);});})();</script></body></html>")
        }
    }

    private fun escapeHtml(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    fun quote(value: String): String = buildString(value.length + 8) {
        append('"')
        for (c in value) {
            when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c.code < 32 || c == '<' || c == '>' || c == '&' || c == '\u2028' || c == '\u2029' ->
                    append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
