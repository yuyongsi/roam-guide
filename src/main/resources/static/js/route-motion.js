'use strict';
// 只在路由切换时淡入；数据保存后的原页刷新保持安静。
const routeMotion=(()=>{
 let current=0,committedUrl=null,timer=null,animation=null;
 const reduced=window.matchMedia('(prefers-reduced-motion: reduce)');
 const progress=()=>document.getElementById('routeProgress');
 function stopAnimation(){animation?.cancel();animation=null;}
 reduced.addEventListener?.('change',()=>{if(reduced.matches)stopAnimation();});
 function begin(id,url,scroll){
  current=id;clearTimeout(timer);stopAnimation();
  const main=document.getElementById('main'),changing=committedUrl!==null&&url!==committedUrl;
  main.setAttribute('aria-busy','true');main.inert=changing;
  // 快速响应不闪烁加载条；慢请求保留旧页面并给出反馈。
  timer=setTimeout(()=>{if(current===id)progress().hidden=false;},150);
  return {id,url,scroll,changing};
 }
 function commit(context,html){
  if(current!==context.id)return false;
  clearTimeout(timer);progress().hidden=true;
  const main=document.getElementById('main');
  main.innerHTML=html;main.inert=false;main.removeAttribute('aria-busy');
  committedUrl=context.url;
  if(Number.isFinite(context.scroll))window.scrollTo({top:context.scroll,left:0,behavior:'instant'});
  if(context.changing){
   main.focus({preventScroll:true});
   if(!reduced.matches&&typeof main.animate==='function'){
    animation=main.animate([{opacity:.25},{opacity:1}],{duration:180,easing:'cubic-bezier(.2,.7,.2,1)'});
   }
  }
  return true;
 }
 return {begin,commit};
})();
