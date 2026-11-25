package vn.jiner.xrealspace

import android.webkit.WebView

/**
 * Browse a web page with a D-pad (XREAL Beam): a highlighted element moves to the nearest link/button in the
 * arrow's direction (spatial navigation done in JS, so it works on the off-screen WebViews the space draws),
 * centre clicks it, and when nothing lies that way the page scrolls. Video: centre = play/pause, ◀▶ = seek.
 */
object PageNav {
    private const val JS = """
(function(){
 if (window.__xsNav) return window.__xsNav;
 var cur = null;
 function vis(e){ var r=e.getBoundingClientRect(); if(r.width<4||r.height<4) return false;
   if(r.bottom<0||r.right<0||r.top>innerHeight||r.left>innerWidth) return false;
   var s=getComputedStyle(e); return s.visibility!=='hidden' && s.display!=='none' && s.opacity!=='0'; }
 function items(){ return Array.prototype.filter.call(document.querySelectorAll(
   'a[href],button,input:not([type=hidden]),select,textarea,summary,video,iframe,[onclick],[role=button],[role=link],[role=tab],[role=menuitem],[tabindex]:not([tabindex="-1"])'), vis); }
 function mark(e){ if(cur){ cur.style.outline=cur.__xo||''; cur.style.outlineOffset=cur.__xoo||''; }
   cur=e; if(!e) return; e.__xo=e.style.outline; e.__xoo=e.style.outlineOffset;
   e.style.outline='4px solid #00E5FF'; e.style.outlineOffset='2px';
   try{ e.scrollIntoView({block:'nearest',inline:'nearest'}); }catch(x){} }
 function center(e){ var r=e.getBoundingClientRect(); return [r.left+r.width/2, r.top+r.height/2]; }
 function move(dx,dy){
   var list=items();
   if(!cur||!document.contains(cur)||!vis(cur)){
     var c=[innerWidth/2,innerHeight/2], best=null, bd=1e12;
     list.forEach(function(e){ var p=center(e), d=(p[0]-c[0])*(p[0]-c[0])+(p[1]-c[1])*(p[1]-c[1]); if(d<bd){bd=d;best=e;} });
     if(best){ mark(best); return 'mark'; } window.scrollBy(dx*innerWidth*0.6, dy*innerHeight*0.6); return 'scroll'; }
   var a=center(cur), best=null, bs=1e12;
   list.forEach(function(e){ if(e===cur||e.contains(cur)||cur.contains(e)) return;
     var p=center(e), ddx=p[0]-a[0], ddy=p[1]-a[1], along=dx?ddx*dx:ddy*dy; if(along<=4) return;
     var cross=dx?Math.abs(ddy):Math.abs(ddx), s=along+cross*2.5; if(s<bs){bs=s;best=e;} });
   if(best){ mark(best); return 'mark'; }
   window.scrollBy(dx*innerWidth*0.6, dy*innerHeight*0.6); return 'scroll'; }
 function video(){ var v=document.querySelector('video'); if(v) return v;
   var f=document.querySelectorAll('iframe'); for(var i=0;i<f.length;i++){ try{ v=f[i].contentDocument.querySelector('video'); if(v) return v; }catch(x){} } return null; }
 function click(){
   if(!cur||!document.contains(cur)){ var v=video(); if(v){ v.paused?v.play():v.pause(); return 'video'; } return 'none'; }
   if(cur.tagName==='VIDEO'){ cur.paused?cur.play():cur.pause(); return 'video'; }
   try{ cur.focus(); }catch(x){} cur.click(); return 'click'; }
 function seek(s){ var v=video(); if(!v) return 'none'; v.currentTime=Math.max(0,v.currentTime+s); return 'seek'; }
 function playPause(){ var v=video(); if(!v) return 'none'; v.paused?v.play():v.pause(); return 'video'; }
 function clear(){ mark(null); cur=null; return 'ok'; }
 window.__xsNav={move:move,click:click,seek:seek,playPause:playPause,clear:clear};
 return window.__xsNav;
})()"""

    private fun run(web: WebView, call: String, done: ((String) -> Unit)? = null) =
        web.evaluateJavascript("$JS.$call") { r -> done?.invoke(r?.trim('"') ?: "") }

    fun move(web: WebView, dx: Int, dy: Int) = run(web, "move($dx,$dy)")
    fun click(web: WebView) = run(web, "click()")
    fun seek(web: WebView, secs: Int) = run(web, "seek($secs)")
    fun playPause(web: WebView) = run(web, "playPause()")
    fun clear(web: WebView) = run(web, "clear()")
}
