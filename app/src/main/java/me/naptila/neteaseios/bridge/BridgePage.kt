package me.naptila.neteaseios.bridge

import java.util.Locale

/** Pure page renderer executed inside the target game's GMWebviewActivity. */
internal object BridgePage {
    fun render(
        appLabel: String,
        base: String
    ): String {
        val guidance = "返回游戏后，先关闭可能已打开的旧登录弹窗，再使用游戏当前提供的「重新登录」或「切换账号」入口重新登录，并核对区服与角色。"
        val badgeText = escapeHtml("网易 iOS 登录桥接 · $appLabel")

        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>")
            append("body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f4f7fc;color:#14213d;font:16px/1.6 -apple-system,BlinkMacSystemFont,sans-serif;padding:20px;box-sizing:border-box}")
            append(".card{max-width:420px;width:100%;background:#fff;border-radius:20px;padding:28px 24px;box-shadow:0 12px 32px rgba(20,33,61,.08);border:1px solid rgba(36,99,235,.10)}")
            append(".badge{display:inline-block;padding:4px 12px;border-radius:999px;background:#eef4ff;color:#2457c5;font-size:12px;font-weight:600;margin-bottom:12px}")
            append("h2{margin:0 0 10px;font-size:22px;color:#0f172a}")
            append("#status{margin:0 0 14px;color:#475569;font-size:15px}")
            append(".warn{margin:0 0 20px;padding:12px 14px;border-radius:12px;background:#fff7ed;border:1px solid #fdba74;color:#9a3412;font-size:13.5px;line-height:1.55;font-weight:600}")
            append("button{appearance:none;border:0;border-radius:12px;background:#1e3a8a;color:#fff;font-size:15px;font-weight:600;padding:12px 20px;width:100%;cursor:pointer}")
            append("</style></head><body>")
            append("<div class=\"card\">")
            append("<span class=\"badge\">").append(badgeText).append("</span>")
            append("<h2>准备 iOS 登录</h2>")
            append("<p id=\"status\">正在读取当前账号…</p>")
            append("<div class=\"warn\">若游戏显示「重新登录」而非「切换账号」，请使用实际提供的入口。若已有登录弹窗，请先关闭再重新打开；勿直接选择旧缓存记录。SDK 未确认时不能判定 iOS 登录成功。</div>")
            append("<button onclick=\"window.ntesBridgeClose()\">返回游戏</button>")
            append("</div>")
            append("<script>(function(){console.log=function(){};window.ntesBridgeClose=userClose;")
            append("var complete=false,sending=false,tries=0,importing=false,callbackSeen=false;")
            append("var base=").append(quote(base)).append(";")
            append("function show(s){document.getElementById('status').textContent=s;}")
            append("function closePage(){AndroidJSBridge.postMsgToNative(JSON.stringify({methodId:'navigation_bar_func',reqData:{methodId:'close',action:'close'}}));}")
            append("function userClose(){if(importing&&!complete){finishImport(false,'manual_return');return;}closePage();}")
            append("function post(route,j){return fetch(base+route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(j)}).then(function(r){if(!r.ok)throw Error();return r.json();});}")
            append("function fail(reason,text){if(complete)return;complete=true;show(text);post('/failed',{reason:reason}).catch(function(){});}")
            append("function finishImport(confirmed,reason){if(complete)return;complete=true;show(confirmed?'SDK 已确认加载请求，请回游戏核对角色。':'请求已发送，SDK 未确认是否接受；请回游戏重新登录并核对角色。');post('/submitted',{confirmed:confirmed,reason:String(reason||'unknown')}).then(function(){show(").append(quote(guidance)).append(");setTimeout(closePage,250);}).catch(function(){show('请求未能确认，请返回游戏核对登录结果。');});}")
            append("function receiveImport(raw){if(complete||!importing)return;callbackSeen=true;try{var j=typeof raw==='string'?JSON.parse(raw):raw;if(!j)return;if(j.errCode===0||j.errCode==='0'){finishImport(true,'sdk_confirmed');return;}if(j.errCode!==undefined&&Number(j.errCode)!==0){var m=String(j.errMsg||'');fail(m==='File content is empty'?'sdk_read_failed':m==='No valid login info found'?'sdk_invalid_session':'sdk_rejected','SDK 未接受本次准备，请返回助手查看结果。');}}catch(e){}}")
            append("function receive(raw){if(complete||sending||importing)return;try{var j=typeof raw==='string'?JSON.parse(raw):raw;if(!j||j.executed_methodId!=='gameContextInfo')return;")
            append("sending=true;show('已读取当前账号，正在注入 iOS 登录准备…');post('/prepare',{full:String(j.user_name||''),game:String(j.game_id||''),channel:String(j.app_channel||'')}).then(function(v){")
            append("if(complete)return;if(!v.ok||!v.command){var r=String(v.reason||'prepare_failed');fail(r,r==='not_logged_in'?'未检测到已登录账号，请先在游戏内登录一次。':'准备文件写入未完成，请返回助手查看原因。');return;}")
            append("importing=true;var cmd=v.command;cmd.callback={nativeCallback:receiveImport};try{window.postMsgToNative(cmd);}catch(e){importing=false;fail('load_not_dispatched','准备文件已写入，但游戏网页桥未能提交 SDK 加载请求。');return;}show('准备文件已写入，已调用游戏 SDK；正在等待回调…');setTimeout(function(){if(!complete&&importing)show('暂未收到可确认的 SDK 回调。可继续等待，或点「返回游戏」自行核对角色。');},12000);setTimeout(function(){if(!complete&&importing)finishImport(false,callbackSeen?'unrecognized_callback':'callback_timeout');},30000);")
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
