"""独立测试库运行：地图检索、操作审计、事务回滚与敏感字段边界。"""
import os,sys,json,sqlite3,uuid,concurrent.futures
from pathlib import Path
from urllib.parse import urlsplit,parse_qs,quote,urlencode
sys.path.insert(0,str(Path(__file__).resolve().parents[3]/'work'))
from http_client import Session
BASE=os.environ.get('ROAM_BASE','http://127.0.0.1:8820')
DB=Path(os.environ['ROAM_QA_DB'])
assert 'work' in DB.parts and ':8818' not in BASE,'只允许独立测试环境'
checks=[]
def check(ok,label):
 assert ok,label
 checks.append(label)
def login(name,password):
 s=Session();token=s.get(BASE+'/api/session').json()['csrf'];r=s.request('POST',BASE+'/api/auth/login',json={'username':name,'password':password},headers={'X-CSRF-Token':token});check(r.status_code==200,'login '+name);return s,{'X-CSRF-Token':r.json()['csrf']}
admin,h=login('admin','TravelGuide!2026');guest=Session()
def call(method,path,body=None):return admin.request(method,BASE+path,json=body,headers=h)
def logs(query=''):return admin.get(BASE+'/api/admin/audit'+query).json()
check(guest.get(BASE+'/api/admin/audit').status_code==401,'anonymous blocked')
user,uh=login('traveler','VisitChina!2026')
check(user.get(BASE+'/api/admin/audit').status_code==403,'ordinary user blocked')
count=admin.get(BASE+'/api/admin/audit').json()['total']
check(user.request('POST',BASE+'/api/admin/users/1/status',json={'status':'BANNED'},headers=uh).status_code==403,'ordinary user management denied')
check(admin.get(BASE+'/api/admin/audit').json()['total']==count,'denied non-admin requests do not impersonate administrator')
with sqlite3.connect(DB) as c:
 spots=c.execute('select s.id,s.title,c.name,c.province from spots s join cities c on c.id=s.city_id where s.published=1 and c.published=1').fetchall()
for id,title,city,province in spots:
 r=guest.get(BASE+'/api/spots/'+str(id));v=r.json();u=urlsplit(v['map_url']);q=parse_qs(u.query)
 check(r.status_code==200 and u.scheme=='https' and u.netloc=='uri.amap.com' and u.path=='/search' and q['city']==[city] and q['keyword']==[v['map_query']] and q['view']==['map'],f'map trusted host and encoded query {id}')
rows=admin.get(BASE+'/api/admin/spots').json();original=next(v for v in rows if v['id']==1);v=dict(original)
query='浙江省 杭州市 西湖 风景区 &入口? #一号 <测试>'
v['map_query']=query;r=call('PUT','/api/admin/spots/1',v);check(r.status_code==200,'custom map saved')
public=guest.get(BASE+'/api/spots/1').json();check(parse_qs(urlsplit(public['map_url']).query)['keyword']==[query],'map metacharacters encoded safely')
log=logs()['items'][0];check(log['action']=='EDIT' and log['target_id']=='1' and log['outcome']=='SUCCESS' and log['actor_name']=='admin','successful edit audited')
check(call('PUT','/api/admin/spots/1',v).status_code==409,'stale edit rejected');check(logs()['items'][0]['http_status']==409 and logs()['items'][0]['outcome']=='FAILED','stale edit logged as failure')
def spot():return next(x for x in admin.get(BASE+'/api/admin/spots').json() if x['id']==1)
v=spot();before=v.copy();v['title']='不得提交的测试标题';v['map_query']='回滚测试';v['theme']='非法主题'
check(call('PUT','/api/admin/spots/1',v).status_code==400,'late validation rejected')
after=spot();check(after==before,'all fields and revision rolled back')
with sqlite3.connect(DB) as c:
 c.execute("CREATE TRIGGER qa_audit_abort BEFORE INSERT ON admin_audit_logs WHEN NEW.outcome='SUCCESS' BEGIN SELECT RAISE(ABORT,'qa forced failure'); END;")
try:
 v=spot();v['map_query']='这次应该回滚';r=call('PUT','/api/admin/spots/1',v);check(r.status_code==500,'audit persistence failure fails mutation');check(spot()==before,'audit failure rolls back business data')
finally:
 with sqlite3.connect(DB) as c:c.execute('DROP TRIGGER qa_audit_abort')
v=spot();v['map_query']='';check(call('PUT','/api/admin/spots/1',v).status_code==200,'blank map fallback accepted');check(guest.get(BASE+'/api/spots/1').json()['map_query']=='浙江 杭州 西湖','blank query falls back to city and title')
v=spot();v['map_query']='老地点';check(call('PUT','/api/admin/spots/1',v).status_code==200,'set map before rename');v=spot();v['title']='西湖测试改名';check(call('PUT','/api/admin/spots/1',v).status_code==200,'rename saved');check(spot()['map_query']=='','rename clears stale unchanged custom query')
v=spot();original['revision']=v['revision'];check(call('PUT','/api/admin/spots/1',original).status_code==200,'restore original spot')
nonce=uuid.uuid4().hex[:10];name='audit_'+nonce
r=guest.get(BASE+'/api/session');register=guest.request('POST',BASE+'/api/auth/register',json={'username':name,'password':'AuditTest!2026'},headers={'X-CSRF-Token':r.json()['csrf']});check(register.status_code==200,'disposable account created')
uid=register.json()['user']['id'];total=logs()['total'];r=call('POST',f'/api/admin/users/{uid}/status',{'status':'BANNED'});check(r.status_code==200,'ban user');check(logs()['items'][0]['summary']=='用户已封禁','ban meaning recorded')
check(call('POST',f'/api/admin/users/{uid}/status',{'status':'ACTIVE'}).status_code==200,'unban user');check(logs()['items'][0]['summary']=='用户已解封','unban meaning recorded')
r=call('POST',f'/api/admin/users/{uid}/reset-password');check(r.status_code==200,'reset test user password');secret=r.json()['temporary_password'];check(secret not in json.dumps(logs('?size=50'),ensure_ascii=False),'temporary password excluded')
items=logs('?q='+quote(name))['items'];check(len(items)==3 and all(x['resource']=='users' for x in items),'target search matches snapshots')
check(logs('?action=RESET_PASSWORD&q='+quote(name))['total']==1,'action filter')
failed=logs('?outcome=FAILED&size=50');check(failed['total']>=3 and all(x['outcome']=='FAILED' for x in failed['items']),'outcome filter')
check(logs('?size=1')['items'][0]['id']>logs('?size=1&page=2')['items'][0]['id'],'stable descending pagination')
for q in ['?page=0','?size=51','?action=BAD','?outcome=BAD','?from=2026-02-30','?from=2026-10-27&to=2026-10-26']:
 check(admin.get(BASE+'/api/admin/audit'+q).status_code==400,'reject invalid filters '+q)
check(call('DELETE','/api/admin/audit/1').status_code in (404,405),'no audit delete endpoint')
check(call('PUT','/api/admin/audit/1',{}).status_code in (404,405),'no audit update endpoint')
check(logs('?q=%25')['total']==0,'LIKE wildcard escaped')
private_body='不应出现在操作记录中的正文_'+nonce
r=user.request('POST',BASE+'/api/guides/1/comments',json={'body':private_body},headers=uh)
check(r.status_code==200,'create disposable comment for moderation test')
comment=r.json()['id'];check(call('DELETE',f'/api/comments/{comment}').status_code==200,'administrator deletes comment')
entry=logs()['items'][0];check(entry['action']=='DELETE_COMMENT' and entry['target_id']==str(comment),'comment deletion audit survives deleted object')
check(private_body not in json.dumps(entry,ensure_ascii=False),'deleted comment body excluded from audit')
# Inclusive Chinese local-date bounds, including exactly midnight and the last millisecond.
with sqlite3.connect(DB) as c:
 for i,t in enumerate(['2026-10-24T15:59:59.999Z','2026-10-24T16:00:00.000Z','2026-10-25T15:59:59.999Z','2026-10-25T16:00:00.000Z']):
  c.execute("insert into admin_audit_logs(actor_id,actor_name,action,resource,target_label,outcome,http_status,summary,request_method,request_path,request_id,created_at)values(1,'date-boundary','EDIT','spots',?,'SUCCESS',200,'测试','PUT','/api/admin/spots/1',?,?)",(str(i),str(uuid.uuid4()),t))
items=logs('?q=date-boundary&from=2026-10-25&to=2026-10-25')['items'];check({x['target_label'] for x in items}=={'1','2'},'Beijing inclusive day boundary')
total=logs()['total'];call('PUT','/api/me/notifications/read',{'all':True});check(logs()['total']==total,'private personal activity excluded')
def concurrent_edit(_):
 s,heads=login('admin','TravelGuide!2026');v=next(x for x in s.get(BASE+'/api/admin/spots').json() if x['id']==1);return s.request('PUT',BASE+'/api/admin/spots/1',json=v,headers=heads).status_code
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(concurrent_edit,range(3)))
check(all(x in (200,409) for x in results) and 200 in results,'concurrent edit audit has no SQLite lock failure')
with sqlite3.connect(DB) as c:
 check(c.execute('pragma integrity_check').fetchone()[0]=='ok','SQLite integrity')
 check(not c.execute('pragma foreign_key_check').fetchall(),'foreign keys')
 columns=[x[1] for x in c.execute('pragma table_info(admin_audit_logs)')]
 check(not any(any(key in col for key in ['password','body','token','cookie']) for col in columns),'audit schema has no secret/body fields')
 check(c.execute('select count(*)=count(distinct request_id) from admin_audit_logs').fetchone()[0]==1,'request identifiers unique')
print('PASS',len(checks),'maps and audit checks')
Path(os.environ.get('ROAM_QA_RESULT','work/map-audit-checks.json')).write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
