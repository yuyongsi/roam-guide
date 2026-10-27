(function(){
 'use strict';
 const key='roam-appearance',root=document.documentElement;
 const moon='<path d="M20.7 13A8.5 8.5 0 0 1 11 3.3 8.5 8.5 0 1 0 20.7 13Z"/>';
 const sun='<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>';
 function apply(value,persist){
  const dark=value==='dark';root.dataset.theme=dark?'dark':'light';
  const meta=document.querySelector('meta[name="theme-color"]');if(meta)meta.content=dark?'#101e27':'#163b49';
  const button=document.querySelector('[data-theme-toggle]');if(button){button.innerHTML='<svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+(dark?sun:moon)+'</svg>';button.setAttribute('aria-label',dark?'切换到白天模式':'切换到黑夜模式');button.setAttribute('aria-pressed',String(dark));button.title='当前：'+(dark?'黑夜':'白天')+'模式';}
  if(persist)try{localStorage.setItem(key,root.dataset.theme);}catch(e){/* 浏览器禁用本地存储时仍可切换。 */}
 }
 let initial='light';try{if(localStorage.getItem(key)==='dark')initial='dark';}catch(e){}
 apply(initial,false);
 document.addEventListener('DOMContentLoaded',()=>apply(root.dataset.theme,false));
 window.addEventListener('storage',e=>{if(e.key===key)apply(e.newValue,false);});
 window.roamTheme={toggle(){apply(root.dataset.theme==='dark'?'light':'dark',true);return root.dataset.theme;}};
})();
