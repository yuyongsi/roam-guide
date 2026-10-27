'use strict';
const communityState={loadVersion:0,busy:new Set()};
function commentReactionButton(c){
 const id=Number(c.id),guide=Number(c.guide_id);
 return '<button type="button" class="comment-reaction" data-community="comment-like" data-comment-like="'+id+'" data-guide-id="'+guide+'" aria-pressed="false" aria-label="点赞这条评论"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" aria-hidden="true"><path d="M7 10h-4v11h4m0-11 5-8c2 0 3 1 2 5l-.5 3H19a2 2 0 0 1 2 2l-2 8a2 2 0 0 1-2 1H7V10Z"/></svg><span data-reaction-label>点赞</span><span data-reaction-count aria-hidden="true">…</span></button>';
}
function applyCommentReaction(row){
 document.querySelectorAll('[data-comment-like="'+Number(row.comment_id)+'"]').forEach(button=>{
  const liked=Boolean(row.liked),count=Number(row.like_count||0);
  button.setAttribute('aria-pressed',String(liked));button.setAttribute('aria-label',(liked?'取消点赞':'点赞这条评论')+'，当前 '+count+' 个赞');
  button.classList.toggle('is-liked',liked);button.dataset.loaded='1';
  button.querySelector('[data-reaction-label]').textContent=liked?'已点赞':'点赞';button.querySelector('[data-reaction-count]').textContent=String(count);
 });
}
async function loadCommentReactions(guideId){
 const version=++communityState.loadVersion;
 try{
  const out=await api('/api/guides/'+Number(guideId)+'/reactions');
  if(version!==communityState.loadVersion)return;
  for(const row of out.reactions)applyCommentReaction(row);
 }catch(error){
  if(version!==communityState.loadVersion)return;
  document.querySelectorAll('[data-comment-like][data-guide-id="'+Number(guideId)+'"]').forEach(button=>{button.title='点赞数量暂时无法读取，可点击重试';button.querySelector('[data-reaction-count]').textContent='—';});
 }
}
async function setCommentLike(commentId,guideId,liked){
 if(communityState.busy.has(commentId))return;
 communityState.busy.add(commentId);++communityState.loadVersion;
 const buttons=Array.from(document.querySelectorAll('[data-comment-like="'+commentId+'"]'));buttons.forEach(b=>b.disabled=true);
 try{
  const row=await api('/api/comments/'+commentId+'/like',{method:liked?'PUT':'DELETE'});applyCommentReaction(row);toast(liked?'已点赞':'已取消点赞');
 }catch(error){toast(error.message,true);if(error.status===401)requireFeatureLogin(()=>setCommentLike(commentId,guideId,true));}
 finally{communityState.busy.delete(commentId);buttons.forEach(b=>b.disabled=false);}
}
async function communityAction(button){
 if(button.dataset.community!=='comment-like')return false;
 const id=Number(button.dataset.commentLike),guide=Number(button.dataset.guideId);
 if(!requireFeatureLogin(()=>setCommentLike(id,guide,true)))return true;
 if(communityState.busy.has(id))return true;
 if(button.dataset.loaded!=='1'){
  button.disabled=true;try{await loadCommentReactions(guide);}finally{button.disabled=false;}
  if(button.dataset.loaded!=='1'){toast('暂时无法读取点赞状态，请稍后重试',true);return true;}
 }
 await setCommentLike(id,guide,button.getAttribute('aria-pressed')!=='true');return true;
}
