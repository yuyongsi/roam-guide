"""Run only against the isolated disposable QA server and database."""
import json, os, sqlite3, time, uuid
from pathlib import Path
from http_client import Session

ROOT=Path(__file__).resolve().parents[1]
BASE=os.environ.get('ROAM_BASE','')
DB=Path(os.environ.get('ROAM_QA_DB',''))
if not BASE.startswith('http://127.0.0.1:883') or not DB.is_file() or 'print-check' not in str(DB).lower():
    raise SystemExit('Set ROAM_BASE to the isolated 883x server and ROAM_QA_DB to its print-check database.')
checks=[]
def check(name,ok):
    if not ok: raise AssertionError(name)
    checks.append(name)
class Client:
    def __init__(self):
        self.s=Session(); self.csrf=self.s.get(BASE+'/api/session').json()['csrf']
    def raw(self,method,path,body=None):
        r=self.s.request(method,BASE+path,json=body,headers={'X-CSRF-Token':self.csrf})
        if r.status_code<400:
            data=r.json()
            if isinstance(data,dict) and 'csrf' in data:self.csrf=data['csrf']
        return r
    def call(self,method,path,body=None):
        r=self.raw(method,path,body);assert r.status_code==200,(path,r.status_code,r.text);return r.json()
def dump():
    with sqlite3.connect(DB) as db:
        return list(db.iterdump())
owner,other,admin,anon=Client(),Client(),Client(),Client()
stamp=uuid.uuid4().hex[:10]
for client,prefix in [(owner,'qa_print_a_'),(other,'qa_print_b_')]:
    client.call('POST','/api/auth/register',{'username':prefix+stamp,'password':'QaTravel!2026'})
admin.call('POST','/api/auth/login',{'username':'admin','password':'TravelGuide!2026'})
created=owner.call('POST','/api/me/trips',{'title':'打印测试','days':2,'notes':'私有行程备注','start_date':'2026-11-01','checklist':[{'key':str(uuid.uuid4()),'text':'确认车票','done':False}],'items':[{'spot_id':1,'day_number':1,'note':'交通备注'}]})
tid=created['id'];base='/api/me/trips/'+str(tid);trip=owner.call('GET',base);schedule=owner.call('GET',base+'/schedule')
owner.call('PUT',base+'/schedule',{'revision':schedule['revision'],'trip_revision':trip['revision'],'days':[{'day_number':1,'start_time':'08:30'},{'day_number':2,'start_time':'09:00'}],'stops':[{'spot_id':1,'travel_minutes':30,'rest_minutes':15}]})
owner.call('POST',base+'/meals',{'day_number':2,'slot':'午餐','food_id':1,'spot_id':0,'planned':'12.34','notes':'私人用餐备注'})
budget=owner.call('GET',base+'/budget');owner.call('PUT',base+'/budget',{'revision':budget['revision'],'people':2,'notes':'预算私人备注','entries':[{'key':str(uuid.uuid4()),'label':'车票','category':'交通','planned':'100.00','actual':'20.00','day_number':1,'spot_id':0}]})
trip=owner.call('GET',base);trip.update({'days':1,'items':[{'spot_id':1,'day_number':1,'note':'交通备注'}]});owner.call('PUT',base,trip)
trip=owner.call('GET',base);schedule=owner.call('GET',base+'/schedule')
before=dump();plain=owner.raw('GET',base+'/print');d=plain.json();with_budget=owner.raw('GET',base+'/print?budget=true');b=with_budget.json()
check('本人可读且禁止缓存',plain.status_code==200 and 'no-store' in plain.headers.get('Cache-Control',''))
check('时间与行程版本一致',d['trip']['revision']==d['schedule']['trip_revision']==trip['revision'] and d['schedule']['revision']==schedule['revision'])
check('时间、交通、备注、清单和越天用餐',d['schedule']['days'][0]['stops'][0]['travel_minutes']==30 and d['trip']['items'][0]['note']=='交通备注' and d['trip']['checklist'][0]['text']=='确认车票' and d['meals'][0]['day_number']==2 and d['meals'][0]['notes']=='私人用餐备注')
check('默认不返回预算或用餐金额','budget' not in d and all('planned_cents' not in m and 'planned' not in m for m in d['meals']))
check('勾选后返回预算和餐费',with_budget.status_code==200 and b['budget']['notes']=='预算私人备注' and b['meals'][0]['planned_cents']==1234)
check('匿名不可读',anon.raw('GET',base+'/print').status_code in (401,403))
check('其他账号不可读',other.raw('GET',base+'/print').status_code in (403,404))
check('管理员不能读取其他人行程',admin.raw('GET',base+'/print').status_code in (403,404))
invalid=owner.raw('GET',base+'/print?budget=invalid');check('非法预算参数被拒绝',400<=invalid.status_code<500)
check('预览请求不修改数据库',before==dump())
out=Path(os.environ.get('ROAM_PRINT_API_RESULT',str(ROOT/'target/print-api-checks.json')));out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps({'passed':len(checks),'checks':checks,'trip_id':tid,'database':str(DB)},ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS',len(checks),'print API checks:',out)
