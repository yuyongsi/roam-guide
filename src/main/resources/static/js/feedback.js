'use strict';
function feedbackStatus(status){return status==='RESOLVED'?'已处理':'待处理';}
function feedbackSource(path){return path?'<a class="text-link" href="'+esc(path)+'">查看相关页面</a>':'';}
function feedbackCard(row){return '<article class="feedback-record"><div class="feedback-record-heading"><strong>'+esc(row.category)+'</strong><span class="status-badge '+(row.status==='RESOLVED'?'':'feedback-pending')+'">'+feedbackStatus(row.status)+'</span><time>'+date(row.created_at)+'</time></div><p class="feedback-copy">'+esc(row.body)+'</p>'+feedbackSource(row.page_path)+(row.admin_reply?'<div class="feedback-reply"><strong>管理员回复</strong><p>'+esc(row.admin_reply)+'</p><span>'+date(row.updated_at)+'</span></div>':'<p class="muted small-text">提交后可在这里查看回复。</p>')+'</article>';}
async function feedbackPage(){
 if(!state.user)return '<div class="container"><div class="page-top"><h1>让漫游更好用</h1><p>发现内容错误，或有功能建议，都可以告诉我们。</p></div><div class="empty">'+icon('comment',38)+'<h2>登录后提交意见反馈</h2><p>反馈内容和管理员回复仅你本人及管理员可见。</p><button class="button" data-auth="login">登录 / 注册</button></div></div>';
 const rows=await api('/api/me/feedback');let source=new URLSearchParams(location.search).get('from')||'';
 if(!/^\/(?:spot\/[1-9][0-9]*|guide\/[1-9][0-9]*|destination\/[a-z0-9-]+)$/.test(source))source='';
 return '<div class="container me-layout">'+profileSidebar(false,'feedback')+'<section class="me-main"><h1>我的意见反馈</h1><p class="muted small-text">反馈和回复仅你本人及管理员可见，最多保留 100 条。</p><form class="form-stack feedback-compose" data-feedback-form="submit"><label for="feedbackCategory">反馈类型</label><select id="feedbackCategory" name="category"><option>内容纠错</option><option>功能建议</option><option>使用问题</option></select><label for="feedbackBody">具体内容</label><textarea id="feedbackBody" name="body" required maxlength="1000" rows="4" placeholder="请描述遇到的问题，或希望增加的功能。"></textarea><label for="feedbackSource">相关页面路径（可选）</label><input id="feedbackSource" name="page_path" maxlength="200" value="'+esc(source)+'" placeholder="例如：/spot/4"><p class="form-help">支持本站景点、攻略和目的地页面路径，不需要填写网址。</p><p class="form-error" role="alert"></p><div><button class="button" type="submit" '+(rows.length>=100?'disabled':'')+'>提交反馈</button></div></form><div class="section-heading feedback-list-heading"><div><h2>反馈记录</h2><p>'+rows.length+' 条反馈，'+rows.filter(x=>x.status==='PENDING').length+' 条待处理。</p></div></div>'+(rows.length?rows.map(feedbackCard).join(''):empty('还没有提交过反馈','上方写下建议，提交后即可查看处理状态。'))+'</section></div>';
}
async function adminFeedbackPage(){
 const p=new URLSearchParams(location.search),status=['PENDING','RESOLVED'].includes(p.get('status'))?p.get('status'):'',q=p.get('q')||'',page=Math.max(1,Number(p.get('page'))||1);
 const d=await api('/api/admin/feedback?status='+status+'&q='+encodeURIComponent(q)+'&page='+page);
 const link=n=>'/admin?section=feedback&status='+status+'&q='+encodeURIComponent(q)+'&page='+n;
 return '<div class="container admin-layout">'+profileSidebar(true,'feedback')+'<section class="admin-main"><h1>意见反馈管理</h1><p class="muted small-text">'+d.pending+' 条待处理，'+d.resolved+' 条已处理。回复会出现在对应用户的反馈记录中。</p><form class="feedback-filters" data-feedback-form="filter"><input name="q" value="'+esc(q)+'" maxlength="100" aria-label="搜索反馈或用户名" placeholder="搜索反馈、用户名或类型"><select name="status" aria-label="反馈处理状态"><option value="">全部状态</option><option value="PENDING" '+(status==='PENDING'?'selected':'')+'>待处理</option><option value="RESOLVED" '+(status==='RESOLVED'?'selected':'')+'>已处理</option></select><button class="button small" type="submit">筛选反馈</button></form><p class="muted small-text">当前条件共 '+d.total+' 条。</p><div class="feedback-admin-list">'+(d.items.length?d.items.map(row=>'<article class="feedback-record"><div class="feedback-record-heading"><strong>'+esc(row.username)+' · '+esc(row.category)+'</strong><span class="status-badge '+(row.status==='RESOLVED'?'':'feedback-pending')+'">'+feedbackStatus(row.status)+'</span><time>'+date(row.created_at)+'</time></div><p class="feedback-copy">'+esc(row.body)+'</p><div class="feedback-admin-actions">'+feedbackSource(row.page_path)+'<button class="button secondary small" data-feedback="reply" data-row="'+esc(JSON.stringify(row))+'">处理 / 查看回复</button></div>'+(row.admin_reply?'<div class="feedback-reply"><strong>当前回复</strong><p>'+esc(row.admin_reply)+'</p></div>':'')+'</article>').join(''):'<p class="muted">没有符合条件的反馈。</p>')+'</div>'+(d.total>20?'<nav class="pagination" aria-label="反馈分页">'+(page>1?'<a href="'+link(page-1)+'">上一页</a>':'')+'<span>第 '+page+' / '+Math.ceil(d.total/20)+' 页</span>'+(page*20<d.total?'<a href="'+link(page+1)+'">下一页</a>':'')+'</nav>':'')+'</section></div>';
}
async function feedbackAction(button){
 if(!button.dataset.feedback)return false;
 if(button.dataset.feedback==='reply'){
  const row=JSON.parse(button.dataset.row);
  featureModal('<h2>处理意见反馈</h2><p class="muted small-text">'+esc(row.username)+' · '+esc(row.category)+' · '+date(row.created_at)+'</p><p class="feedback-modal-original">'+esc(row.body)+'</p>'+feedbackSource(row.page_path)+'<form class="form-stack feedback-reply-form" data-feedback-form="reply" data-id="'+row.id+'"><label for="feedbackReplyStatus">处理状态</label><select id="feedbackReplyStatus" name="status"><option value="PENDING" '+(row.status==='PENDING'?'selected':'')+'>待处理</option><option value="RESOLVED" '+(row.status==='RESOLVED'?'selected':'')+'>已处理</option></select><label for="feedbackReplyBody">回复说明</label><textarea id="feedbackReplyBody" name="admin_reply" maxlength="1000" rows="5" placeholder="标记已处理时，请写明处理结果。">'+esc(row.admin_reply)+'</textarea><p class="form-error" role="alert"></p><button class="button" type="submit">保存处理结果</button></form>');
 }
 return true;
}
async function submitFeedbackForm(form,event){
 const kind=form.dataset.feedbackForm;if(!kind)return false;event.preventDefault();
 const button=form.querySelector('[type=submit]'),errorBox=form.querySelector('.form-error');if(button.disabled)return true;
 button.disabled=true;if(errorBox)errorBox.textContent='';
 try{
  const data=Object.fromEntries(new FormData(form));
  if(kind==='filter')navigate('/admin?section=feedback&status='+encodeURIComponent(data.status)+'&q='+encodeURIComponent(data.q.trim()));
  else if(kind==='submit'){const out=await api('/api/me/feedback',{method:'POST',body:data});await render();toast(out.message);}
  else if(kind==='reply'){const out=await api('/api/admin/feedback/'+form.dataset.id,{method:'PUT',body:data});$('#featureDialog').close();await render();toast(out.message);}
 }catch(error){if(errorBox)errorBox.textContent=error.message;else toast(error.message,true);if(error.status===401)openAuth();}
 finally{button.disabled=false;}
 return true;
}
