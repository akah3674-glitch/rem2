#!/usr/bin/env python3
"""
Rem2 Windows - Tu dong tao tai khoan Replit
Giao dien trinh duyet voi tu dong dien email/mat khau
"""
import webview
import requests
import threading
import time
import json
import os
import sys
from pathlib import Path

SERVER_URL = "https://zkdjjc--hemv5x7n7p.replit.app"
MAIL_PASS  = "Mailtm2025Tool"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0.0.0 Safari/537.36"
)
PREFS_DIR  = Path(os.getenv("APPDATA", ".")) / "rem2"
PREFS_FILE = PREFS_DIR / "accounts.json"

# ---------- State ----------
auto_email    = ""
auto_username = ""
flow_running  = False
accounts      = []
main_window   = None

# ---------- AUTOFILL JS injected into replit.com pages ----------
AUTOFILL_JS_TMPL = r"""
(function(){
  var sid=Date.now()+'-'+Math.random();
  window.__rem2FillSid=sid;
  if(window.__rem2FillMo){try{window.__rem2FillMo.disconnect();}catch(x){}}
  if(window.__rem2FillIv)clearInterval(window.__rem2FillIv);
  var EMAIL='{email}',PASS='{password}',USER='{username}',lastFill=0,emptyTicks=0;
  function fillReact(el,val){
    if(!el||el.value===val)return;
    var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
    if(s&&s.set)s.set.call(el,val);else el.value=val;
    ['input','change'].forEach(function(t){el.dispatchEvent(new Event(t,{bubbles:true}));});
    el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'a'}));
  }
  function findEmail(){return document.querySelector('input[type=email]')||document.querySelector('input[name*=email i]')||document.querySelector('input[placeholder*=email i]');}
  function findPass(){return document.querySelector('input[type=password]')||document.querySelector('input[name*=pass i]');}
  function findUser(){return document.querySelector('input[autocomplete=username]')||document.querySelector('input[name*=user i]')||document.querySelector('input[placeholder*=username i]');}
  function syntheticClick(el){
    if(!el)return;try{el.focus();}catch(e){}
    ['pointerdown','pointerup','mousedown','mouseup','click'].forEach(function(t){
      try{el.dispatchEvent(new(t.startsWith('pointer')?PointerEvent:MouseEvent)(t,{bubbles:true,cancelable:true,isPrimary:true,button:0}));}catch(e){}
    });
    try{el.click();}catch(e){}
  }
  function tick(){
    if(window.__rem2FillSid!==sid)return;
    var now=Date.now();if(now-lastFill<500)return;lastFill=now;
    try{
      var eEl=findEmail(),pEl=findPass(),uEl=findUser();
      if(!eEl&&!pEl){emptyTicks++;if(emptyTicks>8){clearInterval(window.__rem2FillIv);if(window.__rem2FillMo)window.__rem2FillMo.disconnect();}return;}
      emptyTicks=0;
      if(eEl)fillReact(eEl,EMAIL);if(pEl)fillReact(pEl,PASS);if(uEl)fillReact(uEl,USER);
      if(eEl&&!pEl)setTimeout(function(){
        var kws=['continue','next','sign up','create account','get started','submit'];
        var els=document.querySelectorAll('button:not([disabled]),input[type=submit]:not([disabled])');
        for(var i=0;i<els.length;i++){
          var txt=(els[i].innerText||els[i].value||'').trim().toLowerCase();
          for(var k=0;k<kws.length;k++)if(txt.indexOf(kws[k])!==-1){syntheticClick(els[i]);return;}
        }
      },400);
    }catch(err){}
  }
  window.__rem2FillIv=setInterval(function(){if(window.__rem2FillSid!==sid){clearInterval(window.__rem2FillIv);return;}tick();},900);
  var mo=new MutationObserver(function(){tick();});
  mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true});
  window.__rem2FillMo=mo;
  tick();
  (function(){var lastUrl=location.href;function checkUrl(){var u=location.href;if(u!==lastUrl){lastUrl=u;window.__rem2FillSid=null;if(window.__rem2FillIv){clearInterval(window.__rem2FillIv);window.__rem2FillIv=null;}if(window.__rem2FillMo){try{window.__rem2FillMo.disconnect();}catch(e){}}}}setInterval(checkUrl,800);window.addEventListener('popstate',function(){setTimeout(checkUrl,300)});})();
})();
"""

BROWSER_PATCH_JS = """
(function(){
  try{Object.defineProperty(navigator,'webdriver',{get:()=>false});}catch(e){}
  if(!window.chrome){window.chrome={runtime:{},loadTimes:function(){},csi:function(){},app:{}};}
})();
"""

PANEL_JS = r"""
(function(){
  if(document.getElementById('rem2-panel'))return;
  var p=document.createElement('div');
  p.id='rem2-panel';
  p.style.cssText='position:fixed;top:10px;right:10px;z-index:2147483647;background:#1D4ED8;color:#fff;border-radius:10px;padding:12px;min-width:210px;box-shadow:0 4px 16px rgba(0,0,0,.35);font-family:sans-serif;font-size:13px;user-select:none;';
  p.innerHTML=[
    '<div style="font-weight:700;font-size:14px;margin-bottom:8px;letter-spacing:.5px">Rem2</div>',
    '<button id="rem2-start-btn" style="background:#fff;color:#1D4ED8;border:none;padding:7px 0;border-radius:6px;cursor:pointer;width:100%;font-weight:700;font-size:13px;margin-bottom:6px;">Tao tai khoan moi</button>',
    '<button id="rem2-reset-btn" style="background:rgba(255,255,255,.18);color:#fff;border:1px solid rgba(255,255,255,.4);padding:5px 0;border-radius:6px;cursor:pointer;width:100%;font-size:12px;margin-bottom:8px;">Xoa session / Tai lai</button>',
    '<div id="rem2-log" style="font-size:11px;color:#BFDBFE;max-height:120px;overflow-y:auto;line-height:1.5;word-break:break-all;"></div>',
  ].join('');
  document.body.appendChild(p);

  function rem2Log(msg){var d=document.getElementById('rem2-log');if(!d)return;d.innerHTML+=msg+'<br>';d.scrollTop=d.scrollHeight;}
  window.rem2Log=rem2Log;

  document.getElementById('rem2-start-btn').onclick=function(){
    rem2Log('Dang ket noi server...');
    if(window.pywebview&&window.pywebview.api){
      window.pywebview.api.start_flow().then(function(r){
        if(r&&r.error)rem2Log('Loi: '+r.error);
      }).catch(function(e){rem2Log('Loi: '+e);});
    }
  };

  document.getElementById('rem2-reset-btn').onclick=function(){
    rem2Log('Xoa session...');
    if(window.pywebview&&window.pywebview.api){
      window.pywebview.api.reset_session().then(function(){
        location.href='https://replit.com/signup';
      });
    }
  };
})();
"""

# ---------- Storage ----------
def load_accounts():
    global accounts
    try:
        PREFS_DIR.mkdir(parents=True, exist_ok=True)
        if PREFS_FILE.exists():
            with open(PREFS_FILE, encoding="utf-8") as f:
                accounts = json.load(f)
    except Exception:
        accounts = []

def save_accounts():
    try:
        PREFS_DIR.mkdir(parents=True, exist_ok=True)
        with open(PREFS_FILE, "w", encoding="utf-8") as f:
            json.dump(accounts, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"[rem2] save_accounts error: {e}")

# ---------- Flow ----------
def run_flow():
    global auto_email, auto_username, flow_running, accounts

    try:
        # Navigate to signup page first
        if main_window:
            main_window.load_url("https://replit.com/signup")
            time.sleep(1)

        # Request server to create account
        resp = requests.post(f"{SERVER_URL}/api/rem2/create", timeout=30)
        job_id = resp.json()["jobId"]
        _log(f"Server dang xu ly... ID: {job_id[:8]}")

        last_log_idx = 0
        poll_ms = 5.0
        not_found_streak = 0

        for attempt in range(150):
            time.sleep(poll_ms)
            try:
                r = requests.get(f"{SERVER_URL}/api/rem2/status/{job_id}", timeout=30)
                data = r.json()

                if data.get("error"):
                    not_found_streak += 1
                    if not_found_streak == 1 or not_found_streak % 6 == 0:
                        _log("Server dang khoi dong lai, cho them...")
                    continue

                not_found_streak = 0

                # Print server logs
                for entry in (data.get("log") or [])[last_log_idx:]:
                    _log(f"[Cloud] {entry}")
                last_log_idx = len(data.get("log") or [])

                # Got email — inject autofill
                if data.get("email") and not auto_email:
                    auto_email    = data["email"]
                    auto_username = data.get("username", "")
                    poll_ms = 12.0
                    _log(f"Email: {auto_email}")
                    _inject_autofill()

                status = data.get("status", "pending")
                if status == "done":
                    auto_email    = data.get("email", auto_email)
                    auto_username = data.get("username", auto_username)
                    verify_link   = data.get("verifyLink", "")
                    accounts.append({
                        "email":    auto_email,
                        "password": MAIL_PASS,
                        "username": auto_username,
                    })
                    save_accounts()
                    _log(f"Xong! Email: {auto_email} | User: {auto_username}")
                    if verify_link and main_window:
                        main_window.load_url(verify_link)
                    break
                elif status == "error":
                    _log("Server bao loi tao tai khoan")
                    break
                elif attempt > 0 and attempt % 6 == 0:
                    _log(f"Dang cho... {attempt * int(poll_ms)}s")

            except Exception as e:
                if attempt % 12 == 0:
                    print(f"[rem2] poll error: {e}")
        else:
            _log("Het thoi gian cho server")

    except Exception as e:
        _log(f"Loi flow: {e}")
        print(f"[rem2] flow error: {e}")
    finally:
        flow_running = False

def _inject_autofill():
    if not main_window or not auto_email:
        return
    js = (AUTOFILL_JS_TMPL
          .replace("{email}",    auto_email.replace("'", "\\'"))
          .replace("{password}", MAIL_PASS.replace("'", "\\'"))
          .replace("{username}", auto_username.replace("'", "\\'")))
    try:
        main_window.evaluate_js(js)
    except Exception:
        pass

def _log(msg: str):
    """Push message into the floating panel inside the WebView."""
    safe = msg.replace("'", "\\'").replace("\n", " ")
    if main_window:
        try:
            main_window.evaluate_js(f"if(window.rem2Log)window.rem2Log('{safe}');")
        except Exception:
            pass
    print(f"[rem2] {msg}")

# ---------- JS API exposed to webview ----------
class JsApi:
    def start_flow(self):
        global flow_running
        if flow_running:
            return {"error": "Dang chay roi — cho ket thuc truoc"}
        flow_running = True
        threading.Thread(target=run_flow, daemon=True).start()
        return {"ok": True}

    def reset_session(self):
        global auto_email, auto_username
        auto_email    = ""
        auto_username = ""
        return {"ok": True}

    def get_accounts(self):
        return {"count": len(accounts), "accounts": accounts[-20:]}

# ---------- Page-load handler ----------
def on_loaded():
    if not main_window:
        return
    # Browser fingerprint patch
    try:
        main_window.evaluate_js(BROWSER_PATCH_JS)
    except Exception:
        pass
    # Inject floating panel
    try:
        main_window.evaluate_js(PANEL_JS)
    except Exception:
        pass
    # Re-inject autofill if we already have credentials
    if auto_email:
        url = ""
        try:
            url = main_window.get_current_url() or ""
        except Exception:
            pass
        if any(x in url for x in ("signup", "login", "register")):
            _inject_autofill()

# ---------- Entry point ----------
def main():
    global main_window
    load_accounts()

    api = JsApi()
    main_window = webview.create_window(
        title      = "Rem2 — Tao tai khoan Replit",
        url        = "https://replit.com/signup",
        js_api     = api,
        width      = 1280,
        height     = 820,
        user_agent = UA,
        confirm_close = False,
    )
    main_window.events.loaded += on_loaded
    webview.start(debug=False, gui="edgechromium")

if __name__ == "__main__":
    main()
