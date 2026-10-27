'use strict';
// 切页先淡出旧内容，再将新内容移入；原页保存刷新保持安静。
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
  let exit=Promise.resolve();
  if(changing&&!reduced.matches&&typeof main.animate==='function'){
   animation=main.animate([{opacity:1,transform:'translateY(0)'},{opacity:.2,transform:'translateY(-12px)'}],{duration:160,easing:'ease-out',fill:'forwards'});
   exit=animation.finished.catch(()=>{});
  }
  // 快请求只呈现完整转场，慢请求再补充加载反馈。
  timer=setTimeout(()=>{if(current===id)progress().hidden=false;},220);
  return {id,url,scroll,changing,exit};
 }
 async function commit(context,html){
  if(current!==context.id)return false;
  await context.exit;
  // 淡出期间也可能收到下一次导航，不能提交过期的页面。
  if(current!==context.id)return false;
  clearTimeout(timer);stopAnimation();progress().hidden=true;
  const main=document.getElementById('main');
  main.innerHTML=html;main.inert=false;main.removeAttribute('aria-busy');
  committedUrl=context.url;
  if(Number.isFinite(context.scroll))window.scrollTo({top:context.scroll,left:0,behavior:'instant'});
  if(context.changing){
   main.focus({preventScroll:true});
   if(!reduced.matches&&typeof main.animate==='function'){
    animation=main.animate([{opacity:0,transform:'translateY(24px)'},{opacity:1,transform:'translateY(0)'}],{duration:360,easing:'cubic-bezier(.16,1,.3,1)'});
    animation.finished.catch(()=>{});
   }
  }
  return true;
 }
 return {begin,commit};
})();
