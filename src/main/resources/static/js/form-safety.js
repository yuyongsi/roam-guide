'use strict';
// 编辑表单的基线只保存在内存；成功保存的程序化 close 不触发放弃提示。
const formSafety=(()=>{
 const baseline=new WeakMap(),forced=new WeakSet(),closing=new WeakMap();
 const dialogs=[...document.querySelectorAll('#editDialog,#featureDialog')];
 let leaving=null;
 function protectedForm(form){
  return !['pick-spots'].includes(form.dataset.featureForm)&&
   !['route-search','recommend','memory-filter'].includes(form.dataset.expForm);
 }
 function signature(form){
  return JSON.stringify([...form.elements].filter(e=>!['submit','button','reset'].includes(e.type)&&!('draftIgnore'in e.dataset)).map(e=>[
   e.name||e.getAttribute('aria-label')||(e.dataset.sectionTitle!==undefined?'section-title':''),
   e.tagName,e.type,e.type==='file'?[...e.files].map(f=>[f.name,f.size,f.lastModified]):
    ['checkbox','radio'].includes(e.type)?[e.value,e.checked]:e.value
  ]));
 }
 function capture(dialog){
  if(!dialog.open)return;
  for(const form of dialog.querySelectorAll('form'))if(protectedForm(form)&&!baseline.has(form))baseline.set(form,signature(form));
 }
 function dirty(form){return forced.has(form)||(baseline.has(form)&&baseline.get(form)!==signature(form));}
 function dirtyDialogs(){return dialogs.filter(d=>d.open&&[...d.querySelectorAll('form')].some(f=>protectedForm(f)&&dirty(f)));}
 async function requestClose(dialog){
  if(!dialog?.open)return true;
  capture(dialog);
  if(!dirtyDialogs().includes(dialog)){dialog.close();return true;}
  if(closing.has(dialog))return closing.get(dialog);
  const pending=(async()=>{
   const accepted=await confirmAction('这里有尚未保存的内容。放弃后将无法恢复，是否仍要关闭？','放弃未保存的内容？');
   if(accepted&&dialog.open)dialog.close();return accepted;
  })();
  closing.set(dialog,pending);try{return await pending;}finally{closing.delete(dialog);}
 }
 async function allowLeave(){
  if(!dirtyDialogs().length)return true;
  if(leaving)return leaving;
  leaving=(async()=>{
   const accepted=await confirmAction('这里有尚未保存的内容。离开后将无法恢复，是否仍要离开？','离开编辑页面？');
   if(accepted)for(const d of dialogs)if(d.open)d.close();return accepted;
  })();
  try{return await leaving;}finally{leaving=null;}
 }
 for(const dialog of dialogs){
  new MutationObserver(()=>capture(dialog)).observe(dialog,{childList:true,subtree:true,attributes:true,attributeFilter:['open']});
  dialog.addEventListener('cancel',event=>{event.preventDefault();void requestClose(dialog);});
  capture(dialog);
 }
 window.addEventListener('beforeunload',event=>{
  if(dirtyDialogs().length){event.preventDefault();event.returnValue='';}
 });
 return {requestClose,allowLeave,hasDirtyDialogs:()=>dirtyDialogs().length>0,
  isDirty:dirty,markDirty:form=>forced.add(form),capture};
})();
